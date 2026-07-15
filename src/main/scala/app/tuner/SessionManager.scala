package app.tuner

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.typed.{ActorRef, Behavior}
import pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import pekko.stream.{KillSwitches, SharedKillSwitch}
import pekko.stream.scaladsl.Source
import pekko.util.ByteString

import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.UUID

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object SessionManager {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait ChannelKey
  case class Gen4Channel(id: String) extends ChannelKey
  case class LegacyChannel(id: Long) extends ChannelKey

  type SessionId = String

  final case class PlayerSession(
    sessionId: SessionId
  , playlistUrl: String
  , expires: Option[Instant]
  , keepalive: Option[Int]
  )

  final case class Settings(
    openTimeout: FiniteDuration = 10.seconds
  , askTimeout: FiniteDuration = 15.seconds
  , materializationTimeout: FiniteDuration = 5.seconds
  , closeTimeout: FiniteDuration = 5.seconds
  , backpressureTimeout: FiniteDuration = 30.seconds
  , replaceTimeout: FiniteDuration = 10.seconds
  , startupRefreshTimeout: FiniteDuration = 10.seconds
  )

  sealed trait Command

  sealed trait AcquireResult
  final case class Attached(attachmentId: UUID, source: Source[ByteString, NotUsed]) extends AcquireResult
  case object NoAvailableTuners extends AcquireResult
  final case class AcquireFailed(cause: Throwable) extends AcquireResult

  final case class Acquire(channel: ChannelKey, replyTo: ActorRef[AcquireResult]) extends Command

  final case class Replace(
    channel: ChannelKey
  , priorSessionId: SessionId
  , replyTo: ActorRef[ReplaceResult]
  ) extends Command

  sealed trait ReplaceResult
  final case class Replaced(session: PlayerSession) extends ReplaceResult
  final case class ReplaceFailed(cause: Throwable) extends ReplaceResult

  private[tuner] final case class AttachmentStarted(attachmentId: UUID) extends Command
  private[tuner] final case class AttachmentEnded(attachmentId: UUID, cause: Option[Throwable]) extends Command
  private[tuner] final case class AttachmentMaterializeTimeout(attachmentId: UUID) extends Command
  private[tuner] final case class OpenCompleted(
    channel: ChannelKey
  , reservationId: UUID
  , result: Either[Throwable, PlayerSession]
  ) extends Command
  private[tuner] final case class OpenTimedOut(channel: ChannelKey, reservationId: UUID) extends Command
  private[tuner] final case class CloseCompleted(channel: ChannelKey, sessionId: SessionId) extends Command
  private[tuner] final case class CloseTimedOut(channel: ChannelKey, sessionId: SessionId) extends Command
  private[tuner] final case class UpstreamTerminated(channel: ChannelKey, cause: Option[Throwable]) extends Command
  private[tuner] final case class ReplaceClosed(
    channel: ChannelKey
  , priorSessionId: SessionId
  , replyTo: ActorRef[ReplaceResult]
  ) extends Command
  private[tuner] final case class ReplaceOpened(
    channel: ChannelKey
  , priorSessionId: SessionId
  , attemptId: UUID
  , replyTo: ActorRef[ReplaceResult]
  , result: Either[Throwable, PlayerSession]
  ) extends Command
  private[tuner] final case class ReplaceCloseFailed(
    channel: ChannelKey
  , priorSessionId: SessionId
  , replyTo: ActorRef[ReplaceResult]
  , cause: Throwable
  ) extends Command
  private[tuner] final case class ReplaceTimedOut(channel: ChannelKey, priorSessionId: SessionId, attemptId: UUID) extends Command
  private[tuner] final case class SessionUpdated(channel: ChannelKey, session: PlayerSession) extends Command
  private[tuner] final case class TunersUpdated(count: Int) extends Command

  sealed trait Error extends Exception
  object Error {
    case object NoAvailableTuners extends Exception("No available tuners") with Error
    case class OpenTimedOut(channel: String) extends Exception(s"open timed out for $channel") with Error
    case object UpstreamTerminated extends Exception("upstream terminated") with Error
    case object ReplaceAlreadyInProgress extends Exception("replace already in progress") with Error
    case class ReplaceNotActive(channel: String) extends Exception(s"channel not active for replace: $channel") with Error
    case class ReplaceStateChanged(channel: String) extends Exception(s"channel left replacing state: $channel") with Error
    case class ReplaceTimedOut(channel: String) extends Exception(s"replace timed out for $channel") with Error
    case class ReplaceOpenFailed(channel: String, cause: Throwable)
      extends Exception(s"replace open failed for $channel: ${cause.getMessage}", cause) with Error
    case object KeepaliveBoom extends Exception("keepalive boom") with Error
  }

  trait SessionBackend {
    def open(channel: ChannelKey): Future[PlayerSession]
    def close(sessionId: SessionId): Future[Unit]
    def totalTuners: Int
    def refreshTuners(): Future[Int]
  }

  final case class SessionRuntime(
    sessionId: SessionId
  , hubSource: Source[ByteString, NotUsed]
  , stop: () => Unit
  , resumeKeepalive: () => Unit = () => ()
  )

  trait RuntimeFactory {
    def start(
      channel: ChannelKey
    , session: PlayerSession
    , onTerminated: Option[Throwable] => Unit
    , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
    , onSessionUpdated: PlayerSession => Unit
    , backpressureTimeout: FiniteDuration
    , replaceTimeout: FiniteDuration
    ): SessionRuntime
  }

  private final case class PendingAcquire(replyTo: ActorRef[AcquireResult])

  private sealed trait AttachmentState
  private final case class Granted(killSwitch: SharedKillSwitch) extends AttachmentState
  private case object Materialized extends AttachmentState

  private final case class SessionRuntimeState(
    runtime: SessionRuntime
  , attachments: Map[UUID, AttachmentState]
  , queued: Vector[PendingAcquire] = Vector.empty
  )

  private sealed trait ReplacePhase
  private case object ClosingPrior extends ReplacePhase
  private case object OpeningNext extends ReplacePhase
  private case object ReadyToRetryOpen extends ReplacePhase
  private case object WaitingForLateClose extends ReplacePhase

  private sealed trait SessionEntry
  private final case class Opening(
    reservationId: UUID
  , waiters: Vector[PendingAcquire]
  ) extends SessionEntry
  private final case class Active(state: SessionRuntimeState) extends SessionEntry
  private final case class Replacing(
    state: SessionRuntimeState
  , priorSessionId: SessionId
  , phase: ReplacePhase
  , replyTo: Option[ActorRef[ReplaceResult]] = None
  , attemptId: UUID = new UUID(0, 0)
  ) extends SessionEntry
  private final case class Closing(
    sessionId: SessionId
  , waiters: Vector[PendingAcquire] = Vector.empty
  ) extends SessionEntry

  private sealed trait TimerKey
  private final case class MaterializeKey(id: UUID) extends TimerKey
  private final case class OpenKey(channel: ChannelKey, reservationId: UUID) extends TimerKey
  private final case class CloseKey(channel: ChannelKey, sessionId: SessionId) extends TimerKey
  private final case class ReplaceKey(channel: ChannelKey, priorSessionId: SessionId, attemptId: UUID) extends TimerKey
  private case object StartupKey extends TimerKey

  def apply(
    backend: SessionBackend
  , runtimeFactory: RuntimeFactory
  , settings: Settings = Settings()
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val manager = Manager(context, timers, backend, runtimeFactory, settings)
        timers.startSingleTimer(StartupKey, TunersUpdated(backend.totalTuners), settings.startupRefreshTimeout)
        context.pipeToSelf(backend.refreshTuners()) {
          case Success(tuners) => TunersUpdated(tuners)
          case Failure(ex) =>
            log.warn("[session] initial tuners refresh failed", ex)
            TunersUpdated(backend.totalTuners)
        }
        manager.behavior()
      }
    }

  private final case class Manager(
    context: ActorContext[Command]
  , timers: TimerScheduler[Command]
  , backend: SessionBackend
  , runtimeFactory: RuntimeFactory
  , settings: Settings
  ) {
    private implicit val ec: scala.concurrent.ExecutionContext = context.executionContext

    private var channels: Map[ChannelKey, SessionEntry] = Map.empty
    private var sessionIndex: Map[SessionId, ChannelKey] = Map.empty
    private var cachedTuners: Int = backend.totalTuners
    private var startupGate: Option[Vector[(ChannelKey, ActorRef[AcquireResult])]] = Some(Vector.empty)

    def behavior(): Behavior[Command] =
      Behaviors.receiveMessage {
        case Acquire(channel, replyTo) =>
          startupGate match {
            case Some(pending) =>
              startupGate = Some(pending :+ (channel -> replyTo))
              log.info("[session] defer acquire until tuners ready channel={}", ch(channel))
            case None =>
              onAcquire(channel, replyTo)
          }
          Behaviors.same
        case TunersUpdated(count) =>
          timers.cancel(StartupKey)
          cachedTuners = math.max(0, count)
          log.info("[session] tuners updated total={}", cachedTuners)
          startupGate match {
            case Some(pending) =>
              startupGate = None
              pending.foreach { case (channel, replyTo) => onAcquire(channel, replyTo) }
            case None => ()
          }
          Behaviors.same
        case OpenCompleted(channel, reservationId, result) =>
          onOpenCompleted(channel, reservationId, result)
          Behaviors.same
        case OpenTimedOut(channel, reservationId) =>
          onOpenTimedOut(channel, reservationId)
          Behaviors.same
        case AttachmentStarted(attachmentId) =>
          onAttachmentStarted(attachmentId)
          Behaviors.same
        case AttachmentEnded(attachmentId, cause) =>
          onAttachmentEnded(attachmentId, cause)
          Behaviors.same
        case AttachmentMaterializeTimeout(attachmentId) =>
          onAttachmentMaterializeTimeout(attachmentId)
          Behaviors.same
        case UpstreamTerminated(channel, cause) =>
          onUpstreamTerminated(channel, cause)
          Behaviors.same
        case Replace(channel, priorSessionId, replyTo) =>
          onReplace(channel, priorSessionId, replyTo)
          Behaviors.same
        case ReplaceClosed(channel, priorSessionId, replyTo) =>
          onReplaceClosed(channel, priorSessionId, replyTo)
          Behaviors.same
        case ReplaceCloseFailed(channel, priorSessionId, replyTo, cause) =>
          onReplaceCloseFailed(channel, priorSessionId, replyTo, cause)
          Behaviors.same
        case ReplaceOpened(channel, priorSessionId, attemptId, replyTo, result) =>
          onReplaceOpened(channel, priorSessionId, attemptId, replyTo, result)
          Behaviors.same
        case ReplaceTimedOut(channel, priorSessionId, attemptId) =>
          onReplaceTimedOut(channel, priorSessionId, attemptId)
          Behaviors.same
        case SessionUpdated(channel, session) =>
          onSessionUpdated(channel, session)
          Behaviors.same
        case CloseCompleted(channel, sessionId) =>
          onCloseCompleted(channel, sessionId)
          Behaviors.same
        case CloseTimedOut(channel, sessionId) =>
          onCloseTimedOut(channel, sessionId)
          Behaviors.same
      }

    private def reservations: Int = channels.size

    private def ch(channel: ChannelKey): String = channel match {
      case Gen4Channel(id) => id
      case LegacyChannel(id) => id.toString
    }

    private def shortId(id: UUID): String = id.toString.take(8)

    private def clientCount(state: SessionRuntimeState): Int = state.attachments.size

    private def requestTunerRefresh(): Unit =
      context.pipeToSelf(backend.refreshTuners()) {
        case Success(tuners) => TunersUpdated(tuners)
        case Failure(ex) =>
          log.warn("[session] tuners refresh failed", ex)
          TunersUpdated(cachedTuners)
      }

    private def closeOrphan(channel: ChannelKey, sessionId: SessionId): Unit = {
      log.warn("[session] closing orphan session channel={} sessionId={}", ch(channel), sessionId)
      context.pipeToSelf(backend.close(sessionId)) {
        case Success(_) => CloseCompleted(channel, sessionId)
        case Failure(ex) =>
          log.warn("[session] orphan close failed channel={} sessionId={}", ch(channel), sessionId, ex)
          CloseCompleted(channel, sessionId)
      }
    }

    private def armReplaceTimer(channel: ChannelKey, priorSessionId: SessionId, attemptId: UUID): Unit =
      timers.startSingleTimer(
        ReplaceKey(channel, priorSessionId, attemptId)
      , ReplaceTimedOut(channel, priorSessionId, attemptId)
      , settings.replaceTimeout
      )

    private def onAcquire(channel: ChannelKey, replyTo: ActorRef[AcquireResult]): Unit =
      channels.get(channel) match {
        case Some(Opening(reservationId, waiters)) =>
          val pending = waiters.size + 1
          channels = channels.updated(channel, Opening(reservationId, waiters :+ PendingAcquire(replyTo)))
          log.info("[session] client queue channel={} state=opening pending={} reserved={} total={}", ch(channel), pending, reservations, cachedTuners)
        case Some(Active(state)) =>
          replyAttached(channel, state, replyTo, shared = true, Active.apply)
        case Some(Replacing(state, priorSessionId, phase, replaceReply, aid)) =>
          replyAttached(
            channel
          , state
          , replyTo
          , shared = true
          , next => Replacing(next, priorSessionId, phase, replaceReply, aid)
          )
        case Some(Closing(sessionId, waiters)) =>
          channels = channels.updated(channel, Closing(sessionId, waiters :+ PendingAcquire(replyTo)))
          log.info("[session] client queue channel={} sessionId={} state=closing pending={}", ch(channel), sessionId, waiters.size + 1)
        case None =>
          if (cachedTuners <= 0 || reservations >= cachedTuners) {
            log.warn("[session] no available tuners channel={} reserved={} total={}", ch(channel), reservations, cachedTuners)
            requestTunerRefresh()
            replyTo ! NoAvailableTuners
          } else {
            startOpening(channel, Vector(PendingAcquire(replyTo)))
          }
      }

    private def startOpening(channel: ChannelKey, waiters: Vector[PendingAcquire]): Unit = {
      val reservationId = UUID.randomUUID()
      channels = channels.updated(channel, Opening(reservationId, waiters))
      timers.startSingleTimer(OpenKey(channel, reservationId), OpenTimedOut(channel, reservationId), settings.openTimeout)
      log.info("[session] opening channel={} reservation={} waiters={} reserved={} total={}", ch(channel), shortId(reservationId), waiters.size, reservations, cachedTuners)
      context.pipeToSelf(backend.open(channel)) {
        case Success(session) => OpenCompleted(channel, reservationId, Right(session))
        case Failure(ex) => OpenCompleted(channel, reservationId, Left(ex))
      }
    }

    private def onOpenCompleted(
      channel: ChannelKey
    , reservationId: UUID
    , result: Either[Throwable, PlayerSession]
    ): Unit =
      channels.get(channel) match {
        case Some(Opening(id, waiters)) if id == reservationId =>
          timers.cancel(OpenKey(channel, reservationId))
          result match {
            case Right(session) =>
              activate(channel, session, waiters)
            case Left(ex) =>
              channels = channels - channel
              log.warn("[session] open failed channel={} reservation={} waiters={} reserved={}", ch(channel), shortId(reservationId), waiters.size, reservations, ex)
              failWaiters(channel, waiters, ex)
              if (isNoTuners(ex)) requestTunerRefresh()
          }
        case Some(Opening(id, _)) =>
          result match {
            case Right(session) =>
              log.warn("[session] stale open success channel={} expected={} actual={} sessionId={}", ch(channel), shortId(reservationId), shortId(id), session.sessionId)
              closeOrphan(channel, session.sessionId)
            case Left(ex) =>
              log.debug("[session] ignore stale open failure channel={} expected={} actual={}", ch(channel), shortId(reservationId), shortId(id), ex)
          }
        case _ =>
          result match {
            case Right(session) =>
              log.warn("[session] open completed after cancel channel={} sessionId={}", ch(channel), session.sessionId)
              closeOrphan(channel, session.sessionId)
            case Left(ex) =>
              log.debug("[session] ignore late open failure channel={}", ch(channel), ex)
          }
      }

    private def onOpenTimedOut(channel: ChannelKey, reservationId: UUID): Unit =
      channels.get(channel) match {
        case Some(Opening(id, waiters)) if id == reservationId =>
          channels = channels - channel
          val ex = Error.OpenTimedOut(ch(channel))
          log.warn("[session] open timed out channel={} reservation={} waiters={} reserved={}", ch(channel), shortId(reservationId), waiters.size, reservations)
          failWaiters(channel, waiters, ex)
        case _ => ()
      }

    private def activate(
      channel: ChannelKey
    , session: PlayerSession
    , waiters: Vector[PendingAcquire]
    ): Unit = {
      val runtime = runtimeFactory.start(
        channel
      , session
      , cause => context.self ! UpstreamTerminated(channel, cause)
      , (priorId, replyTo) => context.self ! Replace(channel, priorId, replyTo)
      , updated => context.self ! SessionUpdated(channel, updated)
      , settings.backpressureTimeout
      , settings.replaceTimeout * 2 + settings.replaceTimeout / 5
      )
      sessionIndex = sessionIndex.updated(session.sessionId, channel)
      var state = SessionRuntimeState(runtime, Map.empty)
      waiters.foreach { waiter =>
        state = grantAttachment(channel, state, waiter.replyTo, shared = waiters.size > 1)
      }
      channels = channels.updated(channel, Active(state))
      log.info("[session] active channel={} sessionId={} clients={} reserved={} total={}", ch(channel), session.sessionId, clientCount(state), reservations, cachedTuners)
    }

    private def replyAttached(
      channel: ChannelKey
    , state: SessionRuntimeState
    , replyTo: ActorRef[AcquireResult]
    , shared: Boolean
    , wrap: SessionRuntimeState => SessionEntry
    ): Unit = {
      val next = grantAttachment(channel, state, replyTo, shared)
      channels = channels.updated(channel, wrap(next))
    }

    private def grantAttachment(
      channel: ChannelKey
    , state: SessionRuntimeState
    , replyTo: ActorRef[AcquireResult]
    , shared: Boolean
    ): SessionRuntimeState = {
      val attachmentId = UUID.randomUUID()
      val killSwitch = KillSwitches.shared(s"attachment-${attachmentId.toString.take(8)}")
      val source = wrapSource(attachmentId, state.runtime.hubSource, killSwitch)
      timers.startSingleTimer(
        MaterializeKey(attachmentId)
      , AttachmentMaterializeTimeout(attachmentId)
      , settings.materializationTimeout
      )
      replyTo ! Attached(attachmentId, source)
      val next = state.copy(attachments = state.attachments.updated(attachmentId, Granted(killSwitch)))
      log.info("[session] client grant channel={} sessionId={} attachment={} shared={} clients={} reserved={}", ch(channel), state.runtime.sessionId, shortId(attachmentId), shared, clientCount(next), reservations)
      next
    }

    private def wrapSource(
      attachmentId: UUID
    , hub: Source[ByteString, NotUsed]
    , killSwitch: SharedKillSwitch
    ): Source[ByteString, NotUsed] =
      hub
        .via(killSwitch.flow)
        .watchTermination() { (_, done) =>
          context.self ! AttachmentStarted(attachmentId)
          done.onComplete {
            case Success(_) => context.self ! AttachmentEnded(attachmentId, None)
            case Failure(ex) => context.self ! AttachmentEnded(attachmentId, Some(ex))
          }
          NotUsed
        }

    private def onAttachmentStarted(attachmentId: UUID): Unit = {
      findAttachment(attachmentId).foreach { case (channel, entry, state) =>
        state.attachments.get(attachmentId) match {
          case Some(_: Granted) =>
            timers.cancel(MaterializeKey(attachmentId))
            val next = state.copy(attachments = state.attachments.updated(attachmentId, Materialized))
            channels = channels.updated(channel, updateEntry(entry, next))
            log.info("[session] client connect channel={} sessionId={} attachment={} clients={} shared={}", ch(channel), state.runtime.sessionId, shortId(attachmentId), clientCount(next), clientCount(next) > 1)
          case Some(Materialized) =>
            log.debug("[session] ignore duplicate connect channel={} attachment={}", ch(channel), shortId(attachmentId))
          case None => ()
        }
      }
    }

    private def onAttachmentEnded(attachmentId: UUID, cause: Option[Throwable]): Unit = {
      timers.cancel(MaterializeKey(attachmentId))
      findAttachment(attachmentId).foreach { case (channel, entry, state) =>
        val wasShared = clientCount(state) > 1
        val next = state.copy(attachments = state.attachments - attachmentId)
        val remaining = clientCount(next)
        cause match {
          case None =>
            log.info("[session] client disconnect channel={} sessionId={} attachment={} clientsRemaining={} shared={}", ch(channel), state.runtime.sessionId, shortId(attachmentId), remaining, wasShared)
          case Some(ex) =>
            log.warn("[session] client stream failed channel={} sessionId={} attachment={} clientsRemaining={} shared={}", ch(channel), state.runtime.sessionId, shortId(attachmentId), remaining, wasShared, ex)
        }
        if (next.attachments.isEmpty && next.queued.isEmpty)
          beginClosing(channel, next.runtime)
        else
          channels = channels.updated(channel, updateEntry(entry, next))
      }
    }

    private def onAttachmentMaterializeTimeout(attachmentId: UUID): Unit =
      findAttachment(attachmentId).foreach { case (channel, entry, state) =>
        state.attachments.get(attachmentId) match {
          case Some(Granted(killSwitch)) =>
            killSwitch.shutdown()
            val next = state.copy(attachments = state.attachments - attachmentId)
            log.warn("[session] client materialize timeout channel={} sessionId={} attachment={} clientsRemaining={}", ch(channel), state.runtime.sessionId, shortId(attachmentId), clientCount(next))
            if (next.attachments.isEmpty && next.queued.isEmpty)
              beginClosing(channel, next.runtime)
            else
              channels = channels.updated(channel, updateEntry(entry, next))
          case _ => ()
        }
      }

    private def priorClosed(phase: ReplacePhase): Boolean =
      phase == OpeningNext || phase == ReadyToRetryOpen

    private sealed trait BeginCloseMode
    private case object DeleteNow extends BeginCloseMode
    private case object AlreadyDeleted extends BeginCloseMode
    private case object AwaitInFlightDelete extends BeginCloseMode

    private def beginClosing(channel: ChannelKey, runtime: SessionRuntime): Unit = {
      val sessionId = runtime.sessionId
      val (waiters, mode) = channels.get(channel) match {
        case Some(Closing(_, queued)) => (queued, DeleteNow)
        case Some(Replacing(state, prior, phase, replyTo, aid)) =>
          timers.cancel(ReplaceKey(channel, prior, aid))
          replyTo.foreach { ref =>
            ref ! ReplaceFailed(Error.ReplaceStateChanged(ch(channel)))
          }
          val closeMode =
            if (priorClosed(phase)) AlreadyDeleted
            else AwaitInFlightDelete
          (state.queued, closeMode)
        case Some(Active(state)) => (state.queued, DeleteNow)
        case _ => (Vector.empty, DeleteNow)
      }
      channels = channels.updated(channel, Closing(sessionId, waiters))
      try runtime.stop()
      catch {
        case ex: Throwable =>
          log.warn("[session] stop failed channel={} sessionId={}", ch(channel), sessionId, ex)
      }
      mode match {
        case AlreadyDeleted =>
          log.info("[session] closing channel={} sessionId={} priorAlreadyClosed=true reserved={} total={}", ch(channel), sessionId, reservations, cachedTuners)
          onCloseCompleted(channel, sessionId)
        case AwaitInFlightDelete =>
          val wait = settings.closeTimeout.max(settings.replaceTimeout)
          timers.startSingleTimer(CloseKey(channel, sessionId), CloseTimedOut(channel, sessionId), wait)
          log.info("[session] closing channel={} sessionId={} awaitingInFlightReplaceClose=true reserved={} total={}", ch(channel), sessionId, reservations, cachedTuners)
        case DeleteNow =>
          timers.startSingleTimer(CloseKey(channel, sessionId), CloseTimedOut(channel, sessionId), settings.closeTimeout)
          log.info("[session] closing channel={} sessionId={} reserved={} total={}", ch(channel), sessionId, reservations, cachedTuners)
          context.pipeToSelf(backend.close(sessionId)) {
            case Success(_) => CloseCompleted(channel, sessionId)
            case Failure(ex) =>
              log.warn("[session] close failed channel={} sessionId={}", ch(channel), sessionId, ex)
              CloseCompleted(channel, sessionId)
          }
      }
    }

    private def sessionStillTracked(sessionId: SessionId): Boolean =
      channels.exists {
        case (_, Active(state)) => state.runtime.sessionId == sessionId
        case (_, Replacing(state, prior, phase, _, _)) =>
          state.runtime.sessionId == sessionId ||
            (prior == sessionId && !priorClosed(phase))
        case (_, Closing(id, _)) => id == sessionId
        case _ => false
      }

    private def onCloseCompleted(channel: ChannelKey, sessionId: SessionId): Unit =
      channels.get(channel) match {
        case Some(Closing(id, waiters)) if id == sessionId =>
          timers.cancel(CloseKey(channel, sessionId))
          sessionIndex = sessionIndex - sessionId
          channels = channels - channel
          log.info("[session] closed channel={} sessionId={} reserved={} total={} pendingReopen={}", ch(channel), sessionId, reservations, cachedTuners, waiters.size)
          if (waiters.nonEmpty) {
            if (cachedTuners <= 0 || reservations >= cachedTuners) {
              failWaiters(channel, waiters, Error.NoAvailableTuners)
              requestTunerRefresh()
            } else {
              startOpening(channel, waiters)
            }
          }
        case _ =>
          if (!sessionStillTracked(sessionId))
            sessionIndex = sessionIndex - sessionId
      }

    private def onCloseTimedOut(channel: ChannelKey, sessionId: SessionId): Unit =
      channels.get(channel) match {
        case Some(Closing(id, waiters)) if id == sessionId =>
          sessionIndex = sessionIndex - sessionId
          channels = channels - channel
          log.warn("[session] close timed out channel={} sessionId={} reserved={} pendingReopen={}", ch(channel), sessionId, reservations, waiters.size)
          if (waiters.nonEmpty) {
            if (cachedTuners <= 0 || reservations >= cachedTuners) {
              failWaiters(channel, waiters, Error.NoAvailableTuners)
              requestTunerRefresh()
            } else {
              startOpening(channel, waiters)
            }
          }
        case _ => ()
      }

    private def onUpstreamTerminated(channel: ChannelKey, cause: Option[Throwable]): Unit =
      channels.get(channel) match {
        case Some(Active(state)) =>
          cause match {
            case Some(ex) =>
              log.warn("[session] upstream failed channel={} sessionId={} clients={}", ch(channel), state.runtime.sessionId, clientCount(state), ex)
            case None =>
              log.info("[session] upstream complete channel={} sessionId={} clients={}", ch(channel), state.runtime.sessionId, clientCount(state))
          }
          failQueued(channel, state.queued, cause.getOrElse(Error.UpstreamTerminated))
          beginClosing(channel, state.runtime)
        case Some(Replacing(state, _, _, _, _)) =>
          cause match {
            case Some(ex) =>
              log.warn("[session] upstream failed during replace channel={} sessionId={} clients={}", ch(channel), state.runtime.sessionId, clientCount(state), ex)
            case None =>
              log.info("[session] upstream complete during replace channel={} sessionId={} clients={}", ch(channel), state.runtime.sessionId, clientCount(state))
          }
          val terminateCause = cause.getOrElse(Error.UpstreamTerminated)
          failQueued(channel, state.queued, terminateCause)
          beginClosing(channel, state.runtime)
        case _ =>
          cause.foreach { ex =>
            log.debug("[session] ignore upstream terminate channel={} state=absent", ch(channel), ex)
          }
      }

    private def startReplaceOpen(
      channel: ChannelKey
    , state: SessionRuntimeState
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    ): Unit = {
      val aid = UUID.randomUUID()
      channels = channels.updated(channel, Replacing(state, priorSessionId, OpeningNext, Some(replyTo), aid))
      armReplaceTimer(channel, priorSessionId, aid)
      log.info("[session] replace open channel={} prior={} attempt={} clients={}", ch(channel), priorSessionId, shortId(aid), clientCount(state))
      context.pipeToSelf(backend.open(channel)) {
        case Success(session) => ReplaceOpened(channel, priorSessionId, aid, replyTo, Right(session))
        case Failure(ex) => ReplaceOpened(channel, priorSessionId, aid, replyTo, Left(ex))
      }
    }

    private def onReplace(
      channel: ChannelKey
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    ): Unit =
      channels.get(channel) match {
        case Some(Active(state)) if state.runtime.sessionId == priorSessionId =>
          val aid = UUID.randomUUID()
          channels = channels.updated(channel, Replacing(state, priorSessionId, ClosingPrior, Some(replyTo), aid))
          armReplaceTimer(channel, priorSessionId, aid)
          log.info("[session] replacing channel={} sessionId={} attempt={} clients={}", ch(channel), priorSessionId, shortId(aid), clientCount(state))
          context.pipeToSelf(backend.close(priorSessionId)) {
            case Success(_) => ReplaceClosed(channel, priorSessionId, replyTo)
            case Failure(ex) =>
              log.warn("[session] replace close failed channel={} sessionId={}", ch(channel), priorSessionId, ex)
              ReplaceCloseFailed(channel, priorSessionId, replyTo, ex)
          }
        case Some(Replacing(state, prior, ReadyToRetryOpen, _, _)) if prior == priorSessionId =>
          startReplaceOpen(channel, state, priorSessionId, replyTo)
        case Some(Replacing(_, prior, phase, _, _)) if prior == priorSessionId =>
          log.warn("[session] replace already in progress channel={} sessionId={} phase={}", ch(channel), priorSessionId, phase)
          replyTo ! ReplaceFailed(Error.ReplaceAlreadyInProgress)
        case other =>
          log.warn("[session] replace rejected channel={} sessionId={} state={}", ch(channel), priorSessionId, other.map(_.getClass.getSimpleName).getOrElse("absent"))
          replyTo ! ReplaceFailed(Error.ReplaceNotActive(ch(channel)))
      }

    private def onReplaceClosed(
      channel: ChannelKey
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    ): Unit =
      channels.get(channel) match {
        case Some(Replacing(state, prior, ClosingPrior, _, _)) if prior == priorSessionId =>
          sessionIndex = sessionIndex - priorSessionId
          log.info("[session] replace closed prior session channel={} sessionId={}", ch(channel), priorSessionId)
          startReplaceOpen(channel, state, priorSessionId, replyTo)
        case Some(Replacing(state, prior, WaitingForLateClose, _, aid)) if prior == priorSessionId =>
          sessionIndex = sessionIndex - priorSessionId
          channels = channels.updated(channel, Replacing(state, priorSessionId, ReadyToRetryOpen, None, aid))
          log.info("[session] late replace close after timeout channel={} sessionId={}; ready to retry open", ch(channel), priorSessionId)
        case Some(Replacing(_, prior, phase, _, _)) if prior == priorSessionId && priorClosed(phase) =>
          sessionIndex = sessionIndex - priorSessionId
          log.debug("[session] ignore duplicate replace close channel={} sessionId={} phase={}", ch(channel), priorSessionId, phase)
        case Some(Closing(id, _)) if id == priorSessionId =>
          onCloseCompleted(channel, priorSessionId)
        case _ =>
          log.warn("[session] replace close completed after state change channel={} sessionId={}", ch(channel), priorSessionId)
          replyTo ! ReplaceFailed(Error.ReplaceStateChanged(ch(channel)))
      }

    private def onReplaceCloseFailed(
      channel: ChannelKey
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    , cause: Throwable
    ): Unit =
      channels.get(channel) match {
        case Some(Replacing(state, prior, ClosingPrior, _, aid)) if prior == priorSessionId =>
          timers.cancel(ReplaceKey(channel, priorSessionId, aid))
          log.warn("[session] replace close failed, returning to active channel={} sessionId={}", ch(channel), priorSessionId, cause)
          channels = channels.updated(channel, Active(state))
          replyTo ! ReplaceFailed(cause)
        case Some(Replacing(state, prior, WaitingForLateClose, _, _)) if prior == priorSessionId =>
          log.warn("[session] late replace close failed after timeout, returning to active channel={} sessionId={}", ch(channel), priorSessionId, cause)
          channels = channels.updated(channel, Active(state))
          state.runtime.resumeKeepalive()
        case _ =>
          log.debug("[session] ignore late replace close failure channel={} sessionId={}", ch(channel), priorSessionId)
      }

    private def onReplaceOpened(
      channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , result: Either[Throwable, PlayerSession]
    ): Unit =
      channels.get(channel) match {
        case Some(Replacing(state, prior, OpeningNext, _, aid)) if prior == priorSessionId && aid == attemptId =>
          timers.cancel(ReplaceKey(channel, priorSessionId, attemptId))
          result match {
            case Right(session) =>
              sessionIndex = sessionIndex.updated(session.sessionId, channel)
              val nextRuntime = state.runtime.copy(sessionId = session.sessionId)
              val nextState = state.copy(runtime = nextRuntime)
              val waiters = nextState.queued
              var activeState = nextState.copy(queued = Vector.empty)
              waiters.foreach { waiter =>
                activeState = grantAttachment(channel, activeState, waiter.replyTo, shared = true)
              }
              channels = channels.updated(channel, Active(activeState))
              replyTo ! Replaced(session)
              log.info("[session] replaced channel={} prior={} next={} attempt={} clients={} queuedGranted={}", ch(channel), priorSessionId, session.sessionId, shortId(attemptId), clientCount(activeState), waiters.size)
            case Left(ex) =>
              channels = channels.updated(channel, Replacing(state, priorSessionId, ReadyToRetryOpen, None, aid))
              replyTo ! ReplaceFailed(Error.ReplaceOpenFailed(ch(channel), ex))
              if (isNoTuners(ex)) {
                log.warn("[session] replace open no tuners channel={} prior={} clients={}", ch(channel), priorSessionId, clientCount(state))
                requestTunerRefresh()
              } else
                log.warn("[session] replace open failed channel={} prior={} clients={}", ch(channel), priorSessionId, clientCount(state), ex)
          }
        case Some(Replacing(_, prior, OpeningNext, _, aid)) if prior == priorSessionId && aid != attemptId =>
          log.warn("[session] stale replace open attempt channel={} staleAttempt={} currentAttempt={}", ch(channel), shortId(attemptId), shortId(aid))
          result.foreach { session =>
            closeOrphan(channel, session.sessionId)
          }
        case Some(Replacing(_, prior, phase, _, _)) if prior == priorSessionId && phase != OpeningNext =>
          result.foreach { session =>
            log.warn("[session] late replace open after timeout channel={} sessionId={}", ch(channel), session.sessionId)
            closeOrphan(channel, session.sessionId)
          }
        case _ =>
          result.foreach { session =>
            closeOrphan(channel, session.sessionId)
          }
          replyTo ! ReplaceFailed(Error.ReplaceStateChanged(ch(channel)))
      }

    private def onReplaceTimedOut(channel: ChannelKey, priorSessionId: SessionId, attemptId: UUID): Unit =
      channels.get(channel) match {
        case Some(Replacing(state, prior, ClosingPrior, replyTo, aid)) if prior == priorSessionId && aid == attemptId =>
          val ex = Error.ReplaceTimedOut(ch(channel))
          log.warn("[session] replace close timed out channel={} sessionId={} clients={}", ch(channel), priorSessionId, clientCount(state))
          channels = channels.updated(channel, Replacing(state, priorSessionId, WaitingForLateClose, None, aid))
          replyTo.foreach(_ ! ReplaceFailed(ex))
        case Some(Replacing(state, prior, OpeningNext, replyTo, aid)) if prior == priorSessionId && aid == attemptId =>
          val ex = Error.ReplaceTimedOut(ch(channel))
          log.warn("[session] replace open timed out channel={} sessionId={} clients={}", ch(channel), priorSessionId, clientCount(state))
          channels = channels.updated(channel, Replacing(state, priorSessionId, ReadyToRetryOpen, None, aid))
          replyTo.foreach(_ ! ReplaceFailed(ex))
        case _ => ()
      }

    private def onSessionUpdated(channel: ChannelKey, session: PlayerSession): Unit =
      channels.get(channel) match {
        case Some(Active(state)) =>
          val previous = state.runtime.sessionId
          if (previous != session.sessionId) {
            sessionIndex = (sessionIndex - previous).updated(session.sessionId, channel)
            val next = state.copy(runtime = state.runtime.copy(sessionId = session.sessionId))
            channels = channels.updated(channel, Active(next))
            log.info("[session] session id updated channel={} prior={} next={}", ch(channel), previous, session.sessionId)
          }
        case Some(Replacing(state, prior, phase, replyTo, aid)) =>
          if (phase == ClosingPrior || phase == WaitingForLateClose)
            log.debug("[session] ignore session update during replace close channel={} phase={} sessionId={}", ch(channel), phase, session.sessionId)
          else {
            val previous = state.runtime.sessionId
            if (previous != session.sessionId) {
              sessionIndex = (sessionIndex - previous).updated(session.sessionId, channel)
              val next = state.copy(runtime = state.runtime.copy(sessionId = session.sessionId))
              channels = channels.updated(channel, Replacing(next, prior, phase, replyTo, aid))
              log.info("[session] session id updated during replace channel={} prior={} next={}", ch(channel), previous, session.sessionId)
            }
          }
        case _ =>
          log.debug("[session] ignore session update channel={} sessionId={}", ch(channel), session.sessionId)
      }

    private def findAttachment(
      attachmentId: UUID
    ): Option[(ChannelKey, SessionEntry, SessionRuntimeState)] =
      channels.collectFirst {
        case (channel, entry @ Active(state)) if state.attachments.contains(attachmentId) =>
          (channel, entry, state)
        case (channel, entry @ Replacing(state, _, _, _, _)) if state.attachments.contains(attachmentId) =>
          (channel, entry, state)
      }

    private def updateEntry(entry: SessionEntry, state: SessionRuntimeState): SessionEntry =
      entry match {
        case _: Active => Active(state)
        case Replacing(_, prior, phase, replyTo, aid) => Replacing(state, prior, phase, replyTo, aid)
        case other => other
      }

    private def failWaiters(channel: ChannelKey, waiters: Vector[PendingAcquire], ex: Throwable): Unit = {
      if (waiters.nonEmpty)
        log.warn("[session] failing waiters channel={} count={} reason={}", ch(channel), waiters.size, ex.toString)
      waiters.foreach { waiter =>
        if (isNoTuners(ex)) waiter.replyTo ! NoAvailableTuners
        else waiter.replyTo ! AcquireFailed(ex)
      }
    }

    private def failQueued(channel: ChannelKey, queued: Vector[PendingAcquire], ex: Throwable): Unit =
      failWaiters(channel, queued, ex)

    private def isNoTuners(ex: Throwable): Boolean = ex match {
      case Error.NoAvailableTuners => true
      case Tablo4thGen.Error.NoAvailableTuners => true
      case TabloLegacy.Channel.Error.NoAvailableTuners => true
      case other => Option(other.getMessage).exists(_.toLowerCase.contains("no available tuners"))
    }
  }
}
