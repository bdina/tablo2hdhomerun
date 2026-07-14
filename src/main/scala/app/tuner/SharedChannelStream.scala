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

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object SharedChannelStream {
  val log = LoggerFactory.getLogger(this.getClass)

  final case class KeepaliveOps(
    keepalive: PlayerSession => Future[PlayerSession]
  , fetch: PlayerSession => Future[PlayerSession]
  )

  def runtimeFactory(
    keepaliveOpsFor: ChannelKey => Option[KeepaliveOps]
  )(implicit system: ActorSystem[?]): RuntimeFactory =
    new RuntimeFactory {
      def start(
        channel: ChannelKey
      , session: PlayerSession
      , onTerminated: Option[Throwable] => Unit
      , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
      , backpressureTimeout: FiniteDuration
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
        , backpressureTimeout = backpressureTimeout
        )
      }
    }

  def startShared(
    channelLabel: String
  , firstSession: PlayerSession
  , keepaliveOps: Option[KeepaliveOps]
  , onTerminated: Option[Throwable] => Unit
  , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
  , backpressureTimeout: FiniteDuration
  )(implicit system: ActorSystem[?], mat: Materializer, ec: ExecutionContext): SessionRuntime = {
    val currentSession = new AtomicReference(firstSession)
    val streamKillSwitch = new AtomicReference[Option[UniqueKillSwitch]](None)
    val leaseStopped = new AtomicBoolean(false)
    @volatile var keepaliveTask: Option[org.apache.pekko.actor.Cancellable] = None
    var keepaliveMissingLogged = false
    val terminatedReported = new AtomicBoolean(false)

    def cancelKeepalive(): Unit = {
      leaseStopped.set(true)
      keepaliveTask.foreach(_.cancel())
      keepaliveTask = None
    }

    def reportTerminated(cause: Option[Throwable]): Unit =
      if (terminatedReported.compareAndSet(false, true)) {
        cancelKeepalive()
        cause match {
          case Some(ex) =>
            log.warn("[shared] upstream terminated label={} sessionId={}", channelLabel, currentSession.get().sessionId, ex)
          case None =>
            log.info("[shared] upstream complete label={} sessionId={}", channelLabel, currentSession.get().sessionId)
        }
        onTerminated(cause)
      }

    def scheduleKeepaliveRetry(): Unit =
      if (!leaseStopped.get() && keepaliveOps.isDefined) {
        import org.apache.pekko.actor.typed.scaladsl.adapter._
        log.debug("[shared] keepalive retry scheduled label={}", channelLabel)
        keepaliveTask = Some(
          system.toClassic.scheduler.scheduleOnce(
            Tablo4thGen.Channel.WatchSession.keepaliveRetrySec.seconds
          ) { runKeepalive() }(ec)
        )
      }

    def runKeepalive(): Unit =
      keepaliveOps.foreach { ops =>
        if (!leaseStopped.get())
          ops.keepalive(currentSession.get()).onComplete {
            case Success(updated) =>
              if (!leaseStopped.get()) {
                val previous = currentSession.get()
                currentSession.set(updated)
                log.debug(
                  "[shared] keepalive ok label={} expires={} keepalive={}"
                , channelLabel
                , updated.expires.map(_.toString).getOrElse("unknown")
                , updated.keepalive.map(_.toString).getOrElse("unknown")
                )
                if (previous.playlistUrl != updated.playlistUrl) {
                  log.info("[shared] playlist url changed label={}, restarting hls", channelLabel)
                  streamKillSwitch.get().foreach(_.shutdown())
                }
                scheduleKeepalive()
              }
            case Failure(ex) =>
              if (!leaseStopped.get()) {
                log.warn("[shared] keepalive failed label={}", channelLabel, ex)
                ops.fetch(currentSession.get()).foreach { updated =>
                  if (!leaseStopped.get()) currentSession.set(updated)
                }
                scheduleKeepaliveRetry()
              }
          }(ec)
      }

    def scheduleKeepalive(): Unit =
      if (!leaseStopped.get())
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
                    java.time.Duration.between(java.time.Instant.now(), exp).getSeconds.toInt
                  val expiryDelay =
                    math.max(5, untilExpiry - Tablo4thGen.Channel.WatchSession.expiryRetuneLeadSec)
                  if (expiryDelay < delay) delay = expiryDelay
                }
                import org.apache.pekko.actor.typed.scaladsl.adapter._
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
          !java.time.Instant.now().isBefore(
            exp.minusSeconds(Tablo4thGen.Channel.WatchSession.expiryRetuneLeadSec.toLong)
          )
        }

    def replaceSession(prior: PlayerSession): Future[PlayerSession] = {
      val promise = Promise[PlayerSession]()
      log.info(
        "[shared] replace requested label={} sessionId={}"
      , channelLabel
      , prior.sessionId
      )
      val replyAdapter = system.systemActorOf(
        Behaviors.receiveMessage[ReplaceResult] {
          case Replaced(session) =>
            log.info(
              "[shared] replace completed label={} prior={} next={}"
            , channelLabel
            , prior.sessionId
            , session.sessionId
            )
            val _ = promise.trySuccess(session)
            Behaviors.stopped
          case ReplaceFailed(cause) =>
            log.warn(
              "[shared] replace failed label={} sessionId={}"
            , channelLabel
            , prior.sessionId
            , cause
            )
            val _ = promise.tryFailure(cause)
            Behaviors.stopped
        }
      , s"replace-reply-${java.util.UUID.randomUUID().toString.take(8)}"
      )
      requestReplace(prior.sessionId, replyAdapter)
      promise.future
    }

    def streamFromSession(session: PlayerSession): Source[ByteString, ?] = {
      log.info(
        "[shared] stream label={} expires={} keepalive={} playlist={}"
      , channelLabel
      , session.expires.map(_.toString).getOrElse("unknown")
      , session.keepalive.map(_.toString).getOrElse("unknown")
      , LogConfig.truncate(session.playlistUrl)
      )
      StreamBackend().stream(session.playlistUrl, channelLabel)
        .viaMat(KillSwitches.single)(Keep.right)
        .mapMaterializedValue { killSwitch =>
          streamKillSwitch.set(Some(killSwitch))
          killSwitch
        }
    }

    def streamFactory(): Source[ByteString, ?] = {
      val session = currentSession.get()
      if (!shouldRefresh(session))
        streamFromSession(session)
      else
        Source.futureSource(
          replaceSession(session).map { newSession =>
            currentSession.set(newSession)
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
        killSwitch.shutdown()
      }
    )
  }
}
