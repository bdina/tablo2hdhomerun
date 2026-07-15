package app.tuner

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.typed.{ActorRef, ActorSystem}
import pekko.actor.typed.scaladsl.Behaviors
import pekko.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import pekko.stream.scaladsl.{BroadcastHub, Keep, Source}
import pekko.util.ByteString

import org.slf4j.LoggerFactory

import app.stream.{ResilientHlsSource, StreamBackend}
import app.sys.LogConfig
import app.tuner.SessionManager.{
  ChannelKey
, Gen4Channel
, LegacyChannel
, PlayerSession
, ReplaceFailed
, ReplaceResult
, Replaced
, RuntimeFactory
, SessionId
, SessionRuntime
}

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object SharedChannelStream {
  val log = LoggerFactory.getLogger(this.getClass)

  final case class KeepaliveOps(
    keepalive: PlayerSession => Future[PlayerSession]
  , fetch: PlayerSession => Future[PlayerSession]
  )

  private[tuner] final case class ReplaceAttempt(
    generation: Long
  , promise: Promise[PlayerSession]
  )

  private[tuner] sealed trait KeepaliveSettleAction
  private[tuner] case object ScheduleKeepalive extends KeepaliveSettleAction
  private[tuner] case object ScheduleKeepaliveRetry extends KeepaliveSettleAction
  private[tuner] case object KeepaliveIdle extends KeepaliveSettleAction

  private[tuner] def settleKeepaliveAction(
    stillCurrent: Boolean
  , applied: Boolean
  ): KeepaliveSettleAction =
    if (!stillCurrent) KeepaliveIdle
    else if (applied) ScheduleKeepalive
    else ScheduleKeepaliveRetry

  private[tuner] final case class ReplaceAttemptOwner() {
    private val generation = new AtomicLong(0)
    private val active = new AtomicReference[Option[ReplaceAttempt]](None)

    def currentGeneration: Long = generation.get()

    def activeFuture: Option[Future[PlayerSession]] =
      active.get().map(_.promise.future)

    def begin(): Either[SessionManager.Error.ReplaceAlreadyInProgress.type, ReplaceAttempt] = {
      var decided: Either[SessionManager.Error.ReplaceAlreadyInProgress.type, ReplaceAttempt] =
        Left(SessionManager.Error.ReplaceAlreadyInProgress)
      var done = false
      while (!done) {
        val current = active.get()
        if (current.isDefined)
          done = true
        else {
          val attempt = ReplaceAttempt(generation.incrementAndGet(), Promise[PlayerSession]())
          if (active.compareAndSet(None, Some(attempt))) {
            decided = Right(attempt)
            done = true
          }
        }
      }
      decided
    }

    def isCurrent(attempt: ReplaceAttempt): Boolean = active.get() match {
      case Some(current) =>
        current.generation == attempt.generation && (current.promise eq attempt.promise)
      case None =>
        false
    }

    def release(attempt: ReplaceAttempt): Unit = {
      val current = active.get()
      current match {
        case Some(owned) if owned.generation == attempt.generation && (owned.promise eq attempt.promise) =>
          val _ = active.compareAndSet(current, None)
        case _ => ()
      }
    }

    def abort(cause: Throwable): Unit = active.getAndSet(None).foreach { attempt =>
      val _ = attempt.promise.tryFailure(cause)
    }
  }

  final case class SharedRuntimeFactory(
    keepaliveOpsFor: ChannelKey => Option[KeepaliveOps]
  )(implicit system: ActorSystem[?]) extends RuntimeFactory {
    def start(
      channel: ChannelKey
    , session: PlayerSession
    , onTerminated: Option[Throwable] => Unit
    , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
    , onSessionUpdated: PlayerSession => Unit
    , backpressureTimeout: FiniteDuration
    , replaceTimeout: FiniteDuration
    ): SessionRuntime = {
      implicit val mat: Materializer = Materializer(system)
      implicit val ec: ExecutionContext = system.executionContext
      val label = channel match {
        case Gen4Channel(id) => s"4thgen-channel-$id"
        case LegacyChannel(id) => s"legacy-channel-$id"
      }
      startShared(
        channelLabel = label
      , firstSession = session
      , keepaliveOps = keepaliveOpsFor(channel)
      , onTerminated = onTerminated
      , requestReplace = requestReplace
      , onSessionUpdated = onSessionUpdated
      , backpressureTimeout = backpressureTimeout
      , replaceTimeout = replaceTimeout
      )
    }
  }

  def runtimeFactory(
    keepaliveOpsFor: ChannelKey => Option[KeepaliveOps]
  )(implicit system: ActorSystem[?]): RuntimeFactory =
    SharedRuntimeFactory(keepaliveOpsFor)

  def startShared(
    channelLabel: String
  , firstSession: PlayerSession
  , keepaliveOps: Option[KeepaliveOps]
  , onTerminated: Option[Throwable] => Unit
  , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
  , onSessionUpdated: PlayerSession => Unit
  , backpressureTimeout: FiniteDuration
  , replaceTimeout: FiniteDuration
  , replaceAttempts: ReplaceAttemptOwner = ReplaceAttemptOwner()
  , streamBackend: StreamBackend = StreamBackend()
  , now: () => Instant = () => Instant.now()
  , afterKeepaliveProgressCheck: () => Unit = () => ()
  )(implicit system: ActorSystem[?], mat: Materializer, ec: ExecutionContext): SessionRuntime = {
    val currentSession = new AtomicReference(firstSession)
    val streamKillSwitch = new AtomicReference[Option[UniqueKillSwitch]](None)
    val leaseStopped = new AtomicBoolean(false)
    val replaceInProgress = new AtomicBoolean(false)
    val firstStreamAttempt = new AtomicBoolean(true)
    @volatile var keepaliveTask: Option[org.apache.pekko.actor.Cancellable] = None
    @volatile var replaceTimeoutTask: Option[org.apache.pekko.actor.Cancellable] = None
    val activeReplaceAdapter = new AtomicReference[Option[ActorRef[ReplaceResult]]](None)
    var keepaliveMissingLogged = false
    val terminatedReported = new AtomicBoolean(false)

    def pauseKeepalive(): Unit = {
      keepaliveTask.foreach(_.cancel())
      keepaliveTask = None
    }

    def cancelKeepalive(): Unit = {
      leaseStopped.set(true)
      pauseKeepalive()
    }

    def stopReplaceAdapter(adapter: ActorRef[ReplaceResult]): Unit = {
      import org.apache.pekko.actor.typed.scaladsl.adapter._
      try system.toClassic.stop(adapter.toClassic)
      catch {
        case _: Throwable => ()
      }
    }

    def clearReplaceAdapter(): Unit = activeReplaceAdapter.getAndSet(None).foreach(stopReplaceAdapter)

    def releaseReplaceAdapter(adapter: ActorRef[ReplaceResult]): Unit = {
      val current = activeReplaceAdapter.get()
      current match {
        case Some(ref) if ref eq adapter =>
          val _ = activeReplaceAdapter.compareAndSet(current, None)
        case _ => ()
      }
    }

    def abortReplace(cause: Throwable): Unit = {
      replaceTimeoutTask.foreach(_.cancel())
      replaceTimeoutTask = None
      replaceAttempts.abort(cause)
      replaceInProgress.set(false)
      clearReplaceAdapter()
    }

    def reportTerminated(cause: Option[Throwable]): Unit =
      if (terminatedReported.compareAndSet(false, true)) {
        cancelKeepalive()
        abortReplace(cause.getOrElse(SessionManager.Error.UpstreamTerminated))
        cause match {
          case Some(ex) =>
            log.warn("[shared] upstream terminated label={} sessionId={}", channelLabel, currentSession.get().sessionId, ex)
          case None =>
            log.info("[shared] upstream complete label={} sessionId={}", channelLabel, currentSession.get().sessionId)
        }
        onTerminated(cause)
      }

    def scheduleKeepaliveRetry(): Unit =
      if (!leaseStopped.get() && !replaceInProgress.get() && keepaliveOps.isDefined) {
        import org.apache.pekko.actor.typed.scaladsl.adapter._
        pauseKeepalive()
        log.debug("[shared] keepalive retry scheduled label={}", channelLabel)
        keepaliveTask = Some(
          system.toClassic.scheduler.scheduleOnce(
            Tablo4thGen.Channel.WatchSession.keepaliveRetrySec.seconds
          ) { runKeepalive() }(ec)
        )
      }

    def applySessionUpdate(requested: PlayerSession, updated: PlayerSession): Boolean = {
      val current = currentSession.get()
      if (current.sessionId == requested.sessionId) {
        currentSession.set(updated)
        if (requested.sessionId != updated.sessionId)
          onSessionUpdated(updated)
        true
      } else {
        log.debug("[shared] ignore stale session update label={} requested={} current={}", channelLabel, requested.sessionId, current.sessionId)
        false
      }
    }

    def keepaliveStillCurrent(startedGen: Long): Boolean =
      !leaseStopped.get() && !replaceInProgress.get() && startedGen == replaceAttempts.currentGeneration

    def applyKeepaliveSettle(action: KeepaliveSettleAction): Unit =
      action match {
        case ScheduleKeepalive => scheduleKeepalive()
        case ScheduleKeepaliveRetry => scheduleKeepaliveRetry()
        case KeepaliveIdle => ()
      }

    def runKeepalive(): Unit =
      keepaliveOps.foreach { ops =>
        val startedGen = replaceAttempts.currentGeneration
        if (!leaseStopped.get() && !replaceInProgress.get()) {
          afterKeepaliveProgressCheck()
          if (keepaliveStillCurrent(startedGen)) {
            val requested = currentSession.get()
            ops.keepalive(requested).onComplete {
              case Success(updated) =>
                val stillCurrent = keepaliveStillCurrent(startedGen)
                val applied = stillCurrent && applySessionUpdate(requested, updated)
                if (applied) {
                  log.debug("[shared] keepalive ok label={} expires={} keepalive={}", channelLabel, updated.expires.map(_.toString).getOrElse("unknown"), updated.keepalive.map(_.toString).getOrElse("unknown"))
                  if (requested.playlistUrl != updated.playlistUrl) {
                    log.info("[shared] playlist url changed label={} restarting=hls", channelLabel)
                    streamKillSwitch.get().foreach(_.shutdown())
                  }
                } else if (startedGen != replaceAttempts.currentGeneration)
                  log.debug("[shared] ignore stale keepalive success label={} startedGen={} currentGen={}", channelLabel, startedGen, replaceAttempts.currentGeneration)
                applyKeepaliveSettle(settleKeepaliveAction(stillCurrent, applied))
              case Failure(ex) =>
                if (keepaliveStillCurrent(startedGen)) {
                  log.warn("[shared] keepalive failed label={}", channelLabel, ex)
                  val fetchRequested = currentSession.get()
                  ops.fetch(fetchRequested).onComplete {
                    case Success(updated) =>
                      val stillCurrent = keepaliveStillCurrent(startedGen)
                      val applied = stillCurrent && applySessionUpdate(fetchRequested, updated)
                      if (applied && fetchRequested.playlistUrl != updated.playlistUrl) {
                        log.info("[shared] playlist url changed via fetch label={} restarting=hls", channelLabel)
                        streamKillSwitch.get().foreach(_.shutdown())
                      } else if (startedGen != replaceAttempts.currentGeneration)
                        log.debug("[shared] ignore stale keepalive fetch label={} startedGen={} currentGen={}", channelLabel, startedGen, replaceAttempts.currentGeneration)
                      applyKeepaliveSettle(settleKeepaliveAction(stillCurrent, applied))
                    case Failure(fetchEx) =>
                      if (keepaliveStillCurrent(startedGen)) {
                        log.warn("[shared] keepalive fetch failed label={}", channelLabel, fetchEx)
                        scheduleKeepaliveRetry()
                      }
                  }(ec)
                }
            }(ec)
          }
        }
      }

    def scheduleKeepalive(): Unit =
      if (!leaseStopped.get() && !replaceInProgress.get())
        keepaliveOps match {
          case Some(_) =>
            val session = currentSession.get()
            session.keepalive match {
              case Some(keepalive) if session.sessionId.nonEmpty =>
                val lead = math.min(
                  Tablo4thGen.Channel.WatchSession.expiryRetuneLeadSec
                , math.max(1, keepalive / 3)
                )
                var delay = math.max(5, keepalive - lead)
                session.expires.foreach { exp =>
                  val untilExpiry =
                    java.time.Duration.between(now(), exp).getSeconds.toInt
                  val expiryDelay =
                    math.max(5, untilExpiry - Tablo4thGen.Channel.WatchSession.expiryRetuneLeadSec)
                  if (expiryDelay < delay) delay = expiryDelay
                }
                import org.apache.pekko.actor.typed.scaladsl.adapter._
                pauseKeepalive()
                keepaliveTask = Some(
                  system.toClassic.scheduler.scheduleOnce(delay.seconds) { runKeepalive() }(ec)
                )
              case _ =>
                if (!keepaliveMissingLogged) {
                  keepaliveMissingLogged = true
                  log.debug("[shared] keepalive disabled label={}", channelLabel)
                }
            }
          case None => ()
        }

    def shouldRefresh(session: PlayerSession): Boolean =
      session.expires.isEmpty ||
        session.expires.exists { exp =>
          !now().isBefore(
            exp.minusSeconds(Tablo4thGen.Channel.WatchSession.expiryRetuneLeadSec.toLong)
          )
        }

    def shouldResumeKeepaliveAfterReplaceFailure(cause: Throwable): Boolean =
      cause match {
        case _: SessionManager.Error.ReplaceTimedOut => false
        case SessionManager.Error.ReplaceAlreadyInProgress => false
        case _: SessionManager.Error.ReplaceOpenFailed => false
        case _ => true
      }

    def finishReplaceAttempt(attempt: ReplaceAttempt): Unit = {
      replaceAttempts.release(attempt)
      replaceInProgress.set(false)
    }

    def replaceSession(prior: PlayerSession): Future[PlayerSession] =
      if (!replaceInProgress.compareAndSet(false, true))
        Future.failed(SessionManager.Error.ReplaceAlreadyInProgress)
      else
        replaceAttempts.begin() match {
          case Left(already) =>
            replaceInProgress.set(false)
            Future.failed(already)
          case Right(attempt) =>
            pauseKeepalive()
            log.info("[shared] replace requested label={} sessionId={} generation={}", channelLabel, prior.sessionId, attempt.generation)
            import org.apache.pekko.actor.typed.scaladsl.adapter._
            replaceTimeoutTask.foreach(_.cancel())
            clearReplaceAdapter()
            lazy val replyAdapter: ActorRef[ReplaceResult] = system.systemActorOf(
              Behaviors.receiveMessage[ReplaceResult] {
                case Replaced(session) =>
                  if (replaceAttempts.isCurrent(attempt)) {
                    replaceTimeoutTask.foreach(_.cancel())
                    replaceTimeoutTask = None
                  }
                  releaseReplaceAdapter(replyAdapter)
                  if (replaceAttempts.isCurrent(attempt) && attempt.promise.trySuccess(session)) {
                    log.info("[shared] replace completed label={} prior={} next={}", channelLabel, prior.sessionId, session.sessionId)
                    currentSession.set(session)
                    finishReplaceAttempt(attempt)
                    scheduleKeepalive()
                  } else {
                    log.warn("[shared] ignore late replace success label={} sessionId={}", channelLabel, session.sessionId)
                    if (replaceAttempts.isCurrent(attempt))
                      finishReplaceAttempt(attempt)
                  }
                  Behaviors.stopped
                case ReplaceFailed(cause) =>
                  if (replaceAttempts.isCurrent(attempt)) {
                    replaceTimeoutTask.foreach(_.cancel())
                    replaceTimeoutTask = None
                  }
                  releaseReplaceAdapter(replyAdapter)
                  if (replaceAttempts.isCurrent(attempt) && attempt.promise.tryFailure(cause)) {
                    log.warn("[shared] replace failed label={} sessionId={}", channelLabel, prior.sessionId, cause)
                    finishReplaceAttempt(attempt)
                    if (shouldResumeKeepaliveAfterReplaceFailure(cause))
                      scheduleKeepalive()
                  } else {
                    log.debug("[shared] ignore late replace failure label={} sessionId={}", channelLabel, prior.sessionId)
                    if (replaceAttempts.isCurrent(attempt))
                      finishReplaceAttempt(attempt)
                  }
                  Behaviors.stopped
              }
            , s"replace-reply-${java.util.UUID.randomUUID().toString.take(8)}"
            )
            activeReplaceAdapter.set(Some(replyAdapter))
            replaceTimeoutTask = Some(
              system.toClassic.scheduler.scheduleOnce(replaceTimeout) {
                if (replaceAttempts.isCurrent(attempt) &&
                    attempt.promise.tryFailure(SessionManager.Error.ReplaceTimedOut(channelLabel))) {
                  log.warn("[shared] replace reply timed out label={} sessionId={}", channelLabel, prior.sessionId)
                  finishReplaceAttempt(attempt)
                  clearReplaceAdapter()
                }
              }(ec)
            )
            requestReplace(prior.sessionId, replyAdapter)
            attempt.promise.future
        }

    def streamFromSession(session: PlayerSession): Source[ByteString, ?] = {
      log.info("[shared] stream label={} expires={} keepalive={} playlist={}", channelLabel, session.expires.map(_.toString).getOrElse("unknown"), session.keepalive.map(_.toString).getOrElse("unknown"), LogConfig.truncate(session.playlistUrl))
      streamBackend.stream(session.playlistUrl, channelLabel)
        .viaMat(KillSwitches.single)(Keep.right)
        .mapMaterializedValue { killSwitch =>
          streamKillSwitch.set(Some(killSwitch))
          killSwitch
        }
    }

    def streamFactory(): Source[ByteString, ?] = {
      val session = currentSession.get()
      val isFirst = firstStreamAttempt.compareAndSet(true, false)
      val needsReplace =
        shouldRefresh(session) || (keepaliveOps.isEmpty && !isFirst)
      if (!needsReplace)
        streamFromSession(session)
      else
        Source.futureSource(
          replaceSession(session).map { newSession =>
            streamFromSession(newSession)
          }
        )
    }

    val ((killSwitch, upstreamDone), hubSource) =
      ResilientHlsSource(
        streamFactory = () => streamFactory()
      , streamName = channelLabel
      )
        .viaMat(KillSwitches.single)(Keep.right)
        .watchTermination()(Keep.both)
        .toMat(BroadcastHub.sink[ByteString](startAfterNrOfConsumers = 1, bufferSize = 256))(Keep.both)
        .run()

    upstreamDone.onComplete {
      case Success(_) => reportTerminated(None)
      case Failure(ex) => reportTerminated(Some(ex))
    }

    scheduleKeepalive()

    SessionRuntime(
      sessionId = firstSession.sessionId
    , hubSource = hubSource
        .backpressureTimeout(backpressureTimeout)
        .mapMaterializedValue(_ => NotUsed)
    , stop = () => {
        log.info("[shared] stop label={} sessionId={}", channelLabel, currentSession.get().sessionId)
        cancelKeepalive()
        abortReplace(SessionManager.Error.UpstreamTerminated)
        killSwitch.shutdown()
      }
    , resumeKeepalive = () => {
        if (!leaseStopped.get() && !replaceInProgress.get())
          scheduleKeepalive()
      }
    )
  }
}
