package app.tuner

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.typed.{ActorRef, Behavior, PostStop}
import pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import pekko.stream.{KillSwitches, Materializer, OverflowStrategy, QueueOfferResult, SharedKillSwitch, SystemMaterializer}
import pekko.Done
import pekko.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import pekko.util.ByteString

import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object SessionManager {
  val log = LoggerFactory.getLogger(this.getClass)

  final case class ChannelKey(value: String)

  object ChannelKey {
    def apply(value: Long): ChannelKey = ChannelKey(value.toString)
  }

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

  final case class Replace(channel: ChannelKey, priorSessionId: SessionId, replyTo: ActorRef[ReplaceResult]) extends Command

  sealed trait ReplaceResult
  final case class Replaced(session: PlayerSession) extends ReplaceResult
  final case class ReplaceFailed(cause: Throwable) extends ReplaceResult

  private[tuner] sealed trait AttachmentEvent
  private[tuner] case object AttachmentStarted extends AttachmentEvent
  private[tuner] final case class AttachmentEnded(cause: Option[Throwable]) extends AttachmentEvent
  private[tuner] case object AttachmentMaterializeTimedOut extends AttachmentEvent

  private[tuner] final case class AttachmentSignal(attachmentId: UUID, event: AttachmentEvent) extends Command

  private[tuner] sealed trait ReplaceMode
  private[tuner] case object CloseThenOpen extends ReplaceMode
  private[tuner] case object OpenOnly extends ReplaceMode

  private[tuner] sealed trait CloseStepResult
  private[tuner] case object CloseOk extends CloseStepResult
  private[tuner] final case class CloseFailed(cause: Throwable) extends CloseStepResult
  private[tuner] case object CloseTimedOut extends CloseStepResult

  private[tuner] sealed trait OpenStepResult
  private[tuner] final case class OpenOk(session: PlayerSession) extends OpenStepResult
  private[tuner] final case class OpenFailed(cause: Throwable) extends OpenStepResult

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

  private object ProtocolFlows {
    def open(
      backend: SessionBackend
    , channel: ChannelKey
    , timeout: FiniteDuration
    , channelLabel: String
    , onLateSuccess: PlayerSession => Unit
    )(implicit ec: ExecutionContext): Source[Either[Throwable, PlayerSession], NotUsed] = {
      val openFut = backend.open(channel)
      Source.future(
        openFut.transform {
          case Success(session) => Success(Right(session))
          case Failure(ex) => Success(Left(ex))
        }
      )
      .completionTimeout(timeout)
      .recover {
        case _: TimeoutException =>
          openFut.foreach(onLateSuccess)
          Left(Error.OpenTimedOut(channelLabel))
      }
    }

    def close(
      backend: SessionBackend
    , channelLabel: String
    , sessionId: SessionId
    , timeout: FiniteDuration
    )(implicit ec: ExecutionContext): Source[Boolean, NotUsed] =
      Source.future(
        backend.close(sessionId).transform {
          case Success(_) => Success(false)
          case Failure(ex) =>
            log.warn("[session] close failed channel={} sessionId={}", channelLabel, sessionId, ex)
            Success(false)
        }
      )
      .completionTimeout(timeout)
      .recover {
        case _: TimeoutException => true
      }

    def closeStep(
      backend: SessionBackend
    , sessionId: SessionId
    , timeout: FiniteDuration
    , onLateClose: Try[Unit] => Unit
    )(implicit ec: ExecutionContext): Source[CloseStepResult, NotUsed] = {
      val closeFut = backend.close(sessionId)
      Source.future(
        closeFut.transform {
          case Success(_) => Success(CloseOk: CloseStepResult)
          case Failure(ex) => Success(CloseFailed(ex))
        }
      )
      .completionTimeout(timeout)
      .recover {
        case _: TimeoutException =>
          closeFut.onComplete(onLateClose)
          CloseTimedOut
      }
    }

    def openStep(
      backend: SessionBackend
    , channel: ChannelKey
    , channelLabel: String
    , timeout: FiniteDuration
    , onLateOpen: PlayerSession => Unit
    )(implicit ec: ExecutionContext): Source[OpenStepResult, NotUsed] = {
      val openFut = backend.open(channel)
      Source.future(
        openFut.transform {
          case Success(session) => Success(OpenOk(session))
          case Failure(ex) => Success(OpenFailed(Error.ReplaceOpenFailed(channelLabel, ex)))
        }
      )
      .completionTimeout(timeout)
      .recover {
        case _: TimeoutException =>
          openFut.foreach(onLateOpen)
          OpenFailed(Error.ReplaceTimedOut(channelLabel))
      }
    }

    def awaitTimeout(timeout: FiniteDuration): Source[Boolean, NotUsed] =
      Source.future(Future.never)
        .map(_ => false)
        .completionTimeout(timeout)
        .recover {
          case _: TimeoutException => true
        }
  }

  private[tuner] object Router {
    final case class PendingAcquire(replyTo: ActorRef[AcquireResult])

    sealed trait AttachmentState
    final case class Granted(killSwitch: SharedKillSwitch) extends AttachmentState
    case object Materialized extends AttachmentState

    final case class SessionRuntimeState(
      runtime: SessionRuntime
    , attachments: Map[UUID, AttachmentState]
    , queued: Vector[PendingAcquire] = Vector.empty
    )

    sealed trait ReplacePhase
    case object ClosingPrior extends ReplacePhase
    case object OpeningNext extends ReplacePhase
    case object ReadyToRetryOpen extends ReplacePhase
    case object WaitingForLateClose extends ReplacePhase

    sealed trait SessionEntry
    final case class Opening(reservationId: UUID, waiters: Vector[PendingAcquire]) extends SessionEntry
    final case class Active(state: SessionRuntimeState) extends SessionEntry
    final case class Replacing(
      state: SessionRuntimeState
    , priorSessionId: SessionId
    , phase: ReplacePhase
    , replyTo: Option[ActorRef[ReplaceResult]] = None
    , attemptId: UUID = new UUID(0, 0)
    , killSwitch: Option[SharedKillSwitch] = None
    ) extends SessionEntry
    final case class Closing(sessionId: SessionId, waiters: Vector[PendingAcquire] = Vector.empty) extends SessionEntry

    final case class ManagerState(
      channels: Map[ChannelKey, SessionEntry]
    , sessionIndex: Map[SessionId, ChannelKey]
    , cachedTuners: Int
    , startupGate: Option[Vector[(ChannelKey, ActorRef[AcquireResult])]]
    )

    sealed trait ManagerEvent
    final case class AcquireRequested(channel: ChannelKey, replyTo: ActorRef[AcquireResult]) extends ManagerEvent
    final case class ReplaceRequested(channel: ChannelKey, priorSessionId: SessionId, replyTo: ActorRef[ReplaceResult]) extends ManagerEvent
    final case class TunersRefreshed(count: Int) extends ManagerEvent
    final case class OpenCompleted(channel: ChannelKey, reservationId: UUID, result: Either[Throwable, PlayerSession]) extends ManagerEvent
    final case class CloseCompleted(channel: ChannelKey, sessionId: SessionId, timedOut: Boolean) extends ManagerEvent
    final case class ReplaceCloseCompleted(
      channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , result: CloseStepResult
    ) extends ManagerEvent
    final case class ReplaceOpenCompleted(
      channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , result: OpenStepResult
    ) extends ManagerEvent
    final case class ReplaceLateCloseCompleted(channel: ChannelKey, priorSessionId: SessionId, result: Either[Throwable, Unit]) extends ManagerEvent
    final case class AttachmentObserved(attachmentId: UUID, event: AttachmentEvent) extends ManagerEvent
    final case class UpstreamDied(channel: ChannelKey, cause: Option[Throwable]) extends ManagerEvent
    final case class SessionIdChanged(channel: ChannelKey, session: PlayerSession) extends ManagerEvent
    final case class MaterializeDeadline(attachmentId: UUID) extends ManagerEvent
    final case class RuntimeStarted(channel: ChannelKey, runtime: SessionRuntime, waiters: Vector[PendingAcquire]) extends ManagerEvent

    sealed trait Effect
    final case class ReplyAcquire(replyTo: ActorRef[AcquireResult], result: AcquireResult) extends Effect
    final case class ReplyReplace(replyTo: ActorRef[ReplaceResult], result: ReplaceResult) extends Effect
    final case class RunOpen(channel: ChannelKey, reservationId: UUID) extends Effect
    final case class RunClose(channel: ChannelKey, sessionId: SessionId, timeout: FiniteDuration) extends Effect
    final case class RunReplaceClose(channel: ChannelKey, priorSessionId: SessionId, attemptId: UUID, replyTo: ActorRef[ReplaceResult]) extends Effect
    final case class RunReplaceOpen(
      channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , killSwitch: SharedKillSwitch
    ) extends Effect
    final case class AwaitCloseTimeout(channel: ChannelKey, sessionId: SessionId, timeout: FiniteDuration) extends Effect
    final case class StartRuntime(channel: ChannelKey, session: PlayerSession, waiters: Vector[PendingAcquire]) extends Effect
    final case class StopRuntime(runtime: SessionRuntime) extends Effect
    final case class AbortReplace(killSwitch: Option[SharedKillSwitch]) extends Effect
    final case class GrantAttachment(
      channel: ChannelKey
    , attachmentId: UUID
    , killSwitch: SharedKillSwitch
    , hubSource: Source[ByteString, NotUsed]
    , replyTo: ActorRef[AcquireResult]
    , materializeTimeout: FiniteDuration
    ) extends Effect
    final case class CancelMaterialize(attachmentId: UUID) extends Effect
    final case class RefreshTuners(fallback: Int) extends Effect
    final case class ResumeKeepalive(runtime: SessionRuntime) extends Effect
    final case class CloseOrphan(channel: ChannelKey, sessionId: SessionId) extends Effect
    case object CancelStartupTimer extends Effect

    private sealed trait BeginCloseMode
    private case object DeleteNow extends BeginCloseMode
    private case object AlreadyDeleted extends BeginCloseMode
    private case object AwaitInFlightDelete extends BeginCloseMode

    def initialState(cachedTuners: Int): ManagerState =
      ManagerState(Map.empty, Map.empty, cachedTuners, Some(Vector.empty))

    def channelLabel(channel: ChannelKey): String = channel.value

    def isNoTuners(ex: Throwable): Boolean = ex match {
      case Error.NoAvailableTuners => true
      case Tablo4thGen.Error.NoAvailableTuners => true
      case TabloLegacy.Channel.Error.NoAvailableTuners => true
      case other => Option(other.getMessage).exists(_.toLowerCase.contains("no available tuners"))
    }

    def decide(state: ManagerState, event: ManagerEvent, settings: Settings): (ManagerState, Vector[Effect]) =
      event match {
        case AcquireRequested(channel, replyTo) => onAcquireRequested(state, channel, replyTo, settings)
        case ReplaceRequested(channel, priorSessionId, replyTo) => onReplaceRequested(state, channel, priorSessionId, replyTo)
        case TunersRefreshed(count) => onTunersRefreshed(state, count, settings)
        case OpenCompleted(channel, reservationId, result) => onOpenCompleted(state, channel, reservationId, result)
        case RuntimeStarted(channel, runtime, waiters) => onRuntimeStarted(state, channel, runtime, waiters, settings)
        case AttachmentObserved(attachmentId, ev) => onAttachmentObserved(state, attachmentId, ev, settings)
        case MaterializeDeadline(attachmentId) => onMaterializeTimeout(state, attachmentId, settings)
        case UpstreamDied(channel, cause) => onUpstreamDied(state, channel, cause, settings)
        case ReplaceCloseCompleted(channel, priorSessionId, attemptId, replyTo, result) =>
          onReplaceCloseCompleted(state, channel, priorSessionId, attemptId, replyTo, result)
        case ReplaceOpenCompleted(channel, priorSessionId, attemptId, replyTo, result) =>
          onReplaceOpenCompleted(state, channel, priorSessionId, attemptId, replyTo, result, settings)
        case ReplaceLateCloseCompleted(channel, priorSessionId, result) =>
          onReplaceLateCloseCompleted(state, channel, priorSessionId, result)
        case SessionIdChanged(channel, session) => onSessionIdChanged(state, channel, session)
        case CloseCompleted(channel, sessionId, timedOut) => onCloseCompleted(state, channel, sessionId, timedOut)
      }

    private def shortId(id: UUID): String = id.toString.take(8)

    private def priorClosed(phase: ReplacePhase): Boolean = phase == OpeningNext || phase == ReadyToRetryOpen

    private def sessionStillTracked(state: ManagerState, sessionId: SessionId): Boolean =
      state.channels.exists {
        case (_, Active(rs)) => rs.runtime.sessionId == sessionId
        case (_, Replacing(rs, prior, phase, _, _, _)) =>
          rs.runtime.sessionId == sessionId || (prior == sessionId && !priorClosed(phase))
        case (_, Closing(id, _)) => id == sessionId
        case _ => false
      }

    private def findAttachment(state: ManagerState, attachmentId: UUID): Option[(ChannelKey, SessionEntry, SessionRuntimeState)] =
      state.channels.collectFirst {
        case (channel, entry @ Active(rs)) if rs.attachments.contains(attachmentId) => (channel, entry, rs)
        case (channel, entry @ Replacing(rs, _, _, _, _, _)) if rs.attachments.contains(attachmentId) => (channel, entry, rs)
      }

    private def updateEntry(entry: SessionEntry, rs: SessionRuntimeState): SessionEntry = entry match {
      case _: Active => Active(rs)
      case Replacing(_, prior, phase, replyTo, aid, ks) => Replacing(rs, prior, phase, replyTo, aid, ks)
      case other => other
    }

    private def failWaiters(channel: ChannelKey, waiters: Vector[PendingAcquire], ex: Throwable): Vector[Effect] = {
      if (waiters.nonEmpty)
        log.warn("[session] failing waiters channel={} count={} reason={}", channelLabel(channel), waiters.size, ex.toString)
      waiters.map { waiter =>
        if (isNoTuners(ex)) ReplyAcquire(waiter.replyTo, NoAvailableTuners)
        else ReplyAcquire(waiter.replyTo, AcquireFailed(ex))
      }
    }

    private def grantAttachment(
      channel: ChannelKey
    , rs: SessionRuntimeState
    , replyTo: ActorRef[AcquireResult]
    , shared: Boolean
    , reserved: Int
    , settings: Settings
    ): (SessionRuntimeState, Vector[Effect]) = {
      val attachmentId = UUID.randomUUID()
      val killSwitch = KillSwitches.shared(s"attachment-${shortId(attachmentId)}")
      val next = rs.copy(attachments = rs.attachments.updated(attachmentId, Granted(killSwitch)))
      log.info(
        "[session] client grant channel={} sessionId={} attachment={} shared={} clients={} reserved={}"
      , channelLabel(channel), rs.runtime.sessionId, shortId(attachmentId), shared, next.attachments.size, reserved
      )
      val effects = Vector(
        GrantAttachment(channel, attachmentId, killSwitch, rs.runtime.hubSource, replyTo, settings.materializationTimeout)
      )
      (next, effects)
    }

    private def grantAll(
      channel: ChannelKey
    , rs: SessionRuntimeState
    , waiters: Vector[PendingAcquire]
    , shared: Boolean
    , reserved: Int
    , settings: Settings
    ): (SessionRuntimeState, Vector[Effect]) =
      waiters.foldLeft((rs, Vector.empty[Effect])) { case ((accRs, accEffects), waiter) =>
        val (nextRs, effects) = grantAttachment(channel, accRs, waiter.replyTo, shared, reserved, settings)
        (nextRs, accEffects ++ effects)
      }

    private def startOpening(state: ManagerState, channel: ChannelKey, waiters: Vector[PendingAcquire]): (ManagerState, Vector[Effect]) = {
      val reservationId = UUID.randomUUID()
      val next = state.copy(channels = state.channels.updated(channel, Opening(reservationId, waiters)))
      log.info(
        "[session] opening channel={} reservation={} waiters={} reserved={} total={}"
      , channelLabel(channel), shortId(reservationId), waiters.size, next.channels.size, next.cachedTuners
      )
      (next, Vector(RunOpen(channel, reservationId)))
    }

    private def onAcquireRequested(
      state: ManagerState
    , channel: ChannelKey
    , replyTo: ActorRef[AcquireResult]
    , settings: Settings
    ): (ManagerState, Vector[Effect]) =
      state.startupGate match {
        case Some(pending) =>
          log.info("[session] defer acquire until tuners ready channel={}", channelLabel(channel))
          (state.copy(startupGate = Some(pending :+ (channel -> replyTo))), Vector.empty)
        case None =>
          onAcquire(state, channel, replyTo, settings)
      }

    private def onAcquire(
      state: ManagerState
    , channel: ChannelKey
    , replyTo: ActorRef[AcquireResult]
    , settings: Settings
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Opening(reservationId, waiters)) =>
          val pending = waiters.size + 1
          val next = state.copy(channels = state.channels.updated(channel, Opening(reservationId, waiters :+ PendingAcquire(replyTo))))
          log.info(
            "[session] client queue channel={} state=opening pending={} reserved={} total={}"
          , channelLabel(channel), pending, next.channels.size, next.cachedTuners
          )
          (next, Vector.empty)
        case Some(Active(rs)) =>
          val (nextRs, effects) = grantAttachment(channel, rs, replyTo, shared = true, state.channels.size, settings)
          (state.copy(channels = state.channels.updated(channel, Active(nextRs))), effects)
        case Some(Replacing(rs, priorSessionId, phase, replaceReply, aid, ks)) =>
          val (nextRs, effects) = grantAttachment(channel, rs, replyTo, shared = true, state.channels.size, settings)
          (state.copy(channels = state.channels.updated(channel, Replacing(nextRs, priorSessionId, phase, replaceReply, aid, ks))), effects)
        case Some(Closing(sessionId, waiters)) =>
          val next = state.copy(channels = state.channels.updated(channel, Closing(sessionId, waiters :+ PendingAcquire(replyTo))))
          log.info(
            "[session] client queue channel={} sessionId={} state=closing pending={}"
          , channelLabel(channel), sessionId, waiters.size + 1
          )
          (next, Vector.empty)
        case None =>
          if (state.cachedTuners <= 0 || state.channels.size >= state.cachedTuners) {
            log.warn("[session] no available tuners channel={} reserved={} total={}", channelLabel(channel), state.channels.size, state.cachedTuners)
            (state, Vector(RefreshTuners(state.cachedTuners), ReplyAcquire(replyTo, NoAvailableTuners)))
          } else
            startOpening(state, channel, Vector(PendingAcquire(replyTo)))
      }

    private def onTunersRefreshed(state: ManagerState, count: Int, settings: Settings): (ManagerState, Vector[Effect]) = {
      val cached = math.max(0, count)
      log.info("[session] tuners updated total={}", cached)
      val refreshed = state.copy(cachedTuners = cached)
      refreshed.startupGate match {
        case Some(pending) =>
          val gateless = refreshed.copy(startupGate = None)
          val (finalState, effects) = pending.foldLeft((gateless, Vector.empty[Effect])) { case ((accState, accEffects), (channel, replyTo)) =>
            val (nextState, effects) = onAcquire(accState, channel, replyTo, settings)
            (nextState, accEffects ++ effects)
          }
          (finalState, CancelStartupTimer +: effects)
        case None =>
          (refreshed, Vector(CancelStartupTimer))
      }
    }

    private def onOpenCompleted(
      state: ManagerState
    , channel: ChannelKey
    , reservationId: UUID
    , result: Either[Throwable, PlayerSession]
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Opening(id, waiters)) if id == reservationId =>
          result match {
            case Right(session) =>
              (state, Vector(StartRuntime(channel, session, waiters)))
            case Left(ex) =>
              val next = state.copy(channels = state.channels - channel)
              ex match {
                case _: Error.OpenTimedOut =>
                  log.warn(
                    "[session] open timed out channel={} reservation={} waiters={} reserved={}"
                  , channelLabel(channel), shortId(reservationId), waiters.size, next.channels.size
                  )
                case _ =>
                  log.warn(
                    "[session] open failed channel={} reservation={} waiters={} reserved={}"
                  , channelLabel(channel), shortId(reservationId), waiters.size, next.channels.size, ex
                  )
              }
              val failEffects = failWaiters(channel, waiters, ex)
              val effects = if (isNoTuners(ex)) failEffects :+ RefreshTuners(next.cachedTuners) else failEffects
              (next, effects)
          }
        case Some(Opening(id, _)) =>
          result match {
            case Right(session) =>
              log.warn(
                "[session] stale open success channel={} expected={} actual={} sessionId={}"
              , channelLabel(channel), shortId(reservationId), shortId(id), session.sessionId
              )
              (state, Vector(CloseOrphan(channel, session.sessionId)))
            case Left(ex) =>
              log.debug(
                "[session] ignore stale open failure channel={} expected={} actual={}"
              , channelLabel(channel), shortId(reservationId), shortId(id), ex
              )
              (state, Vector.empty)
          }
        case _ =>
          result match {
            case Right(session) =>
              log.warn("[session] open completed after cancel channel={} sessionId={}", channelLabel(channel), session.sessionId)
              (state, Vector(CloseOrphan(channel, session.sessionId)))
            case Left(ex) =>
              log.debug("[session] ignore late open failure channel={}", channelLabel(channel), ex)
              (state, Vector.empty)
          }
      }

    private def onRuntimeStarted(
      state: ManagerState
    , channel: ChannelKey
    , runtime: SessionRuntime
    , waiters: Vector[PendingAcquire]
    , settings: Settings
    ): (ManagerState, Vector[Effect]) = {
      val indexed = state.copy(sessionIndex = state.sessionIndex.updated(runtime.sessionId, channel))
      val initial = SessionRuntimeState(runtime, Map.empty)
      val (rs, effects) = grantAll(channel, initial, waiters, waiters.size > 1, indexed.channels.size, settings)
      val next = indexed.copy(channels = indexed.channels.updated(channel, Active(rs)))
      log.info(
        "[session] active channel={} sessionId={} clients={} reserved={} total={}"
      , channelLabel(channel), runtime.sessionId, rs.attachments.size, next.channels.size, next.cachedTuners
      )
      (next, effects)
    }

    private def onAttachmentObserved(
      state: ManagerState
    , attachmentId: UUID
    , event: AttachmentEvent
    , settings: Settings
    ): (ManagerState, Vector[Effect]) =
      event match {
        case AttachmentStarted => onAttachmentStarted(state, attachmentId)
        case AttachmentEnded(cause) => onAttachmentEnded(state, attachmentId, cause, settings)
        case AttachmentMaterializeTimedOut => onMaterializeTimeout(state, attachmentId, settings)
      }

    private def onAttachmentStarted(state: ManagerState, attachmentId: UUID): (ManagerState, Vector[Effect]) =
      findAttachment(state, attachmentId) match {
        case Some((channel, entry, rs)) =>
          rs.attachments.get(attachmentId) match {
            case Some(_: Granted) =>
              val next = rs.copy(attachments = rs.attachments.updated(attachmentId, Materialized))
              val nextState = state.copy(channels = state.channels.updated(channel, updateEntry(entry, next)))
              log.info(
                "[session] client connect channel={} sessionId={} attachment={} clients={} shared={}"
              , channelLabel(channel), rs.runtime.sessionId, shortId(attachmentId), next.attachments.size, next.attachments.size > 1
              )
              (nextState, Vector(CancelMaterialize(attachmentId)))
            case Some(Materialized) =>
              log.debug("[session] ignore duplicate connect channel={} attachment={}", channelLabel(channel), shortId(attachmentId))
              (state, Vector.empty)
            case None =>
              (state, Vector.empty)
          }
        case None =>
          (state, Vector.empty)
      }

    private def onAttachmentEnded(
      state: ManagerState
    , attachmentId: UUID
    , cause: Option[Throwable]
    , settings: Settings
    ): (ManagerState, Vector[Effect]) = {
      val cancelEffect = Vector(CancelMaterialize(attachmentId))
      findAttachment(state, attachmentId) match {
        case Some((channel, entry, rs)) =>
          val wasShared = rs.attachments.size > 1
          val next = rs.copy(attachments = rs.attachments - attachmentId)
          val remaining = next.attachments.size
          cause match {
            case None =>
              log.info(
                "[session] client disconnect channel={} sessionId={} attachment={} clientsRemaining={} shared={}"
              , channelLabel(channel), rs.runtime.sessionId, shortId(attachmentId), remaining, wasShared
              )
            case Some(ex) =>
              log.warn(
                "[session] client stream failed channel={} sessionId={} attachment={} clientsRemaining={} shared={}"
              , channelLabel(channel), rs.runtime.sessionId, shortId(attachmentId), remaining, wasShared, ex
              )
          }
          if (next.attachments.isEmpty && next.queued.isEmpty) {
            val (closedState, closeEffects) = beginClosing(state, channel, next.runtime, settings)
            (closedState, cancelEffect ++ closeEffects)
          } else {
            val nextState = state.copy(channels = state.channels.updated(channel, updateEntry(entry, next)))
            (nextState, cancelEffect)
          }
        case None =>
          (state, cancelEffect)
      }
    }

    private def onMaterializeTimeout(
      state: ManagerState
    , attachmentId: UUID
    , settings: Settings
    ): (ManagerState, Vector[Effect]) =
      findAttachment(state, attachmentId) match {
        case Some((channel, entry, rs)) =>
          rs.attachments.get(attachmentId) match {
            case Some(Granted(killSwitch)) =>
              val next = rs.copy(attachments = rs.attachments - attachmentId)
              log.warn(
                "[session] client materialize timeout channel={} sessionId={} attachment={} clientsRemaining={}"
              , channelLabel(channel), rs.runtime.sessionId, shortId(attachmentId), next.attachments.size
              )
              if (next.attachments.isEmpty && next.queued.isEmpty) {
                val (closedState, closeEffects) = beginClosing(state, channel, next.runtime, settings)
                (closedState, AbortReplace(Some(killSwitch)) +: closeEffects)
              } else {
                val nextState = state.copy(channels = state.channels.updated(channel, updateEntry(entry, next)))
                (nextState, Vector(AbortReplace(Some(killSwitch))))
              }
            case _ =>
              (state, Vector.empty)
          }
        case None =>
          (state, Vector.empty)
      }

    private def beginClosing(
      state: ManagerState
    , channel: ChannelKey
    , runtime: SessionRuntime
    , settings: Settings
    ): (ManagerState, Vector[Effect]) = {
      val sessionId = runtime.sessionId
      val (waiters, mode, abortEffects) = state.channels.get(channel) match {
        case Some(Closing(_, queued)) => (queued, DeleteNow, Vector.empty[Effect])
        case Some(Replacing(rs, _, phase, replyTo, _, ks)) =>
          val replyEffects = replyTo.map(ref => ReplyReplace(ref, ReplaceFailed(Error.ReplaceStateChanged(channelLabel(channel))))).toVector
          val closeMode = if (priorClosed(phase)) AlreadyDeleted else AwaitInFlightDelete
          (rs.queued, closeMode, AbortReplace(ks) +: replyEffects)
        case Some(Active(rs)) => (rs.queued, DeleteNow, Vector.empty[Effect])
        case _ => (Vector.empty[PendingAcquire], DeleteNow, Vector.empty[Effect])
      }
      val next = state.copy(channels = state.channels.updated(channel, Closing(sessionId, waiters)))
      val stopEffect = StopRuntime(runtime)
      mode match {
        case AlreadyDeleted =>
          log.info(
            "[session] closing channel={} sessionId={} priorAlreadyClosed=true reserved={} total={}"
          , channelLabel(channel), sessionId, next.channels.size, next.cachedTuners
          )
          val (finalState, finishEffects) = onCloseCompleted(next, channel, sessionId, timedOut = false)
          (finalState, abortEffects ++ (stopEffect +: finishEffects))
        case AwaitInFlightDelete =>
          val wait = settings.closeTimeout.max(settings.replaceTimeout)
          log.info(
            "[session] closing channel={} sessionId={} awaitingInFlightReplaceClose=true reserved={} total={}"
          , channelLabel(channel), sessionId, next.channels.size, next.cachedTuners
          )
          (next, abortEffects ++ Vector(stopEffect, AwaitCloseTimeout(channel, sessionId, wait)))
        case DeleteNow =>
          log.info(
            "[session] closing channel={} sessionId={} reserved={} total={}"
          , channelLabel(channel), sessionId, next.channels.size, next.cachedTuners
          )
          (next, abortEffects ++ Vector(stopEffect, RunClose(channel, sessionId, settings.closeTimeout)))
      }
    }

    private def onCloseCompleted(
      state: ManagerState
    , channel: ChannelKey
    , sessionId: SessionId
    , timedOut: Boolean
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Closing(id, waiters)) if id == sessionId =>
          val cleared = state.copy(sessionIndex = state.sessionIndex - sessionId, channels = state.channels - channel)
          if (timedOut)
            log.warn(
              "[session] close timed out channel={} sessionId={} reserved={} pendingReopen={}"
            , channelLabel(channel), sessionId, cleared.channels.size, waiters.size
            )
          else
            log.info(
              "[session] closed channel={} sessionId={} reserved={} total={} pendingReopen={}"
            , channelLabel(channel), sessionId, cleared.channels.size, cleared.cachedTuners, waiters.size
            )
          if (waiters.nonEmpty) {
            if (cleared.cachedTuners <= 0 || cleared.channels.size >= cleared.cachedTuners) {
              val failEffects = failWaiters(channel, waiters, Error.NoAvailableTuners)
              (cleared, failEffects :+ RefreshTuners(cleared.cachedTuners))
            } else
              startOpening(cleared, channel, waiters)
          } else
            (cleared, Vector.empty)
        case _ =>
          if (!sessionStillTracked(state, sessionId))
            (state.copy(sessionIndex = state.sessionIndex - sessionId), Vector.empty)
          else
            (state, Vector.empty)
      }

    private def onUpstreamDied(
      state: ManagerState
    , channel: ChannelKey
    , cause: Option[Throwable]
    , settings: Settings
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Active(rs)) =>
          cause match {
            case Some(ex) =>
              log.warn("[session] upstream failed channel={} sessionId={} clients={}", channelLabel(channel), rs.runtime.sessionId, rs.attachments.size, ex)
            case None =>
              log.info("[session] upstream complete channel={} sessionId={} clients={}", channelLabel(channel), rs.runtime.sessionId, rs.attachments.size)
          }
          val failEffects = failWaiters(channel, rs.queued, cause.getOrElse(Error.UpstreamTerminated))
          val (nextState, closeEffects) = beginClosing(state, channel, rs.runtime, settings)
          (nextState, failEffects ++ closeEffects)
        case Some(Replacing(rs, _, _, _, _, ks)) =>
          cause match {
            case Some(ex) =>
              log.warn(
                "[session] upstream failed during replace channel={} sessionId={} clients={}"
              , channelLabel(channel), rs.runtime.sessionId, rs.attachments.size, ex
              )
            case None =>
              log.info(
                "[session] upstream complete during replace channel={} sessionId={} clients={}"
              , channelLabel(channel), rs.runtime.sessionId, rs.attachments.size
              )
          }
          val terminateCause = cause.getOrElse(Error.UpstreamTerminated)
          val failEffects = failWaiters(channel, rs.queued, terminateCause)
          val (nextState, closeEffects) = beginClosing(state, channel, rs.runtime, settings)
          (nextState, AbortReplace(ks) +: (failEffects ++ closeEffects))
        case _ =>
          cause.foreach(ex => log.debug("[session] ignore upstream terminate channel={} state=absent", channelLabel(channel), ex))
          (state, Vector.empty)
      }

    private def onSessionIdChanged(state: ManagerState, channel: ChannelKey, session: PlayerSession): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Active(rs)) =>
          val previous = rs.runtime.sessionId
          if (previous != session.sessionId) {
            val nextRs = rs.copy(runtime = rs.runtime.copy(sessionId = session.sessionId))
            val next = state.copy(
              sessionIndex = (state.sessionIndex - previous).updated(session.sessionId, channel)
            , channels = state.channels.updated(channel, Active(nextRs))
            )
            log.info("[session] session id updated channel={} prior={} next={}", channelLabel(channel), previous, session.sessionId)
            (next, Vector.empty)
          } else
            (state, Vector.empty)
        case Some(Replacing(rs, prior, phase, replyTo, aid, ks)) =>
          if (phase == ClosingPrior || phase == WaitingForLateClose) {
            log.debug("[session] ignore session update during replace close channel={} phase={} sessionId={}", channelLabel(channel), phase, session.sessionId)
            (state, Vector.empty)
          } else {
            val previous = rs.runtime.sessionId
            if (previous != session.sessionId) {
              val nextRs = rs.copy(runtime = rs.runtime.copy(sessionId = session.sessionId))
              val next = state.copy(
                sessionIndex = (state.sessionIndex - previous).updated(session.sessionId, channel)
              , channels = state.channels.updated(channel, Replacing(nextRs, prior, phase, replyTo, aid, ks))
              )
              log.info("[session] session id updated during replace channel={} prior={} next={}", channelLabel(channel), previous, session.sessionId)
              (next, Vector.empty)
            } else
              (state, Vector.empty)
          }
        case _ =>
          log.debug("[session] ignore session update channel={} sessionId={}", channelLabel(channel), session.sessionId)
          (state, Vector.empty)
      }

    private def startReplaceAttempt(
      state: ManagerState
    , channel: ChannelKey
    , rs: SessionRuntimeState
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    , mode: ReplaceMode
    , phase: ReplacePhase
    ): (ManagerState, Vector[Effect]) = {
      val aid = UUID.randomUUID()
      val killSwitch = KillSwitches.shared(s"replace-${shortId(aid)}")
      val next = state.copy(channels = state.channels.updated(channel, Replacing(rs, priorSessionId, phase, Some(replyTo), aid, Some(killSwitch))))
      mode match {
        case CloseThenOpen =>
          log.info("[session] replacing channel={} sessionId={} attempt={} clients={}", channelLabel(channel), priorSessionId, shortId(aid), rs.attachments.size)
          (next, Vector(RunReplaceClose(channel, priorSessionId, aid, replyTo)))
        case OpenOnly =>
          log.info("[session] replace open channel={} prior={} attempt={} clients={}", channelLabel(channel), priorSessionId, shortId(aid), rs.attachments.size)
          (next, Vector(RunReplaceOpen(channel, priorSessionId, aid, replyTo, killSwitch)))
      }
    }

    private def onReplaceRequested(
      state: ManagerState
    , channel: ChannelKey
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Active(rs)) if rs.runtime.sessionId == priorSessionId =>
          startReplaceAttempt(state, channel, rs, priorSessionId, replyTo, CloseThenOpen, ClosingPrior)
        case Some(Replacing(rs, prior, ReadyToRetryOpen, _, _, _)) if prior == priorSessionId =>
          startReplaceAttempt(state, channel, rs, priorSessionId, replyTo, OpenOnly, OpeningNext)
        case Some(Replacing(_, prior, phase, _, _, _)) if prior == priorSessionId =>
          log.warn("[session] replace already in progress channel={} sessionId={} phase={}", channelLabel(channel), priorSessionId, phase)
          (state, Vector(ReplyReplace(replyTo, ReplaceFailed(Error.ReplaceAlreadyInProgress))))
        case other =>
          log.warn(
            "[session] replace rejected channel={} sessionId={} state={}"
          , channelLabel(channel), priorSessionId, other.map(_.getClass.getSimpleName).getOrElse("absent")
          )
          (state, Vector(ReplyReplace(replyTo, ReplaceFailed(Error.ReplaceNotActive(channelLabel(channel))))))
      }

    private def onReplaceCloseSucceeded(
      state: ManagerState
    , channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: Option[ActorRef[ReplaceResult]]
    , phase: ReplacePhase
    , rs: SessionRuntimeState
    , aid: UUID
    , ks: Option[SharedKillSwitch]
    ): (ManagerState, Vector[Effect]) =
      phase match {
        case ClosingPrior =>
          ks match {
            case Some(killSwitch) =>
              val next = state.copy(
                sessionIndex = state.sessionIndex - priorSessionId
              , channels = state.channels.updated(channel, Replacing(rs, priorSessionId, OpeningNext, replyTo, aid, Some(killSwitch)))
              )
              log.info("[session] replace closed prior session channel={} sessionId={}", channelLabel(channel), priorSessionId)
              val runEffect = replyTo match {
                case Some(ref) => Vector(RunReplaceOpen(channel, priorSessionId, attemptId, ref, killSwitch))
                case None => Vector.empty
              }
              (next, runEffect)
            case None =>
              log.warn("[session] replace close completed without kill switch channel={} sessionId={}", channelLabel(channel), priorSessionId)
              (state, replyTo.map(ref => ReplyReplace(ref, ReplaceFailed(Error.ReplaceStateChanged(channelLabel(channel))))).toVector)
          }
        case WaitingForLateClose =>
          val next = state.copy(
            sessionIndex = state.sessionIndex - priorSessionId
          , channels = state.channels.updated(channel, Replacing(rs, priorSessionId, ReadyToRetryOpen, None, aid, None))
          )
          log.info("[session] late replace close after timeout channel={} sessionId={}; ready to retry open", channelLabel(channel), priorSessionId)
          (next, Vector.empty)
        case other if priorClosed(other) =>
          log.debug("[session] ignore duplicate replace close channel={} sessionId={} phase={}", channelLabel(channel), priorSessionId, other)
          (state.copy(sessionIndex = state.sessionIndex - priorSessionId), Vector.empty)
        case other =>
          log.warn("[session] replace close completed after state change channel={} sessionId={} phase={}", channelLabel(channel), priorSessionId, other)
          (state, replyTo.map(ref => ReplyReplace(ref, ReplaceFailed(Error.ReplaceStateChanged(channelLabel(channel))))).toVector)
      }

    private def onReplaceCloseFailed(
      state: ManagerState
    , channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: Option[ActorRef[ReplaceResult]]
    , cause: Throwable
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Replacing(rs, prior, ClosingPrior, _, aid, ks)) if prior == priorSessionId && aid == attemptId =>
          log.warn("[session] replace close failed, returning to active channel={} sessionId={}", channelLabel(channel), priorSessionId, cause)
          val next = state.copy(channels = state.channels.updated(channel, Active(rs)))
          val replyEffect = replyTo.map(ref => ReplyReplace(ref, ReplaceFailed(cause))).toVector
          (next, AbortReplace(ks) +: replyEffect)
        case Some(Replacing(rs, prior, WaitingForLateClose, _, _, _)) if prior == priorSessionId =>
          log.warn("[session] late replace close failed after timeout, returning to active channel={} sessionId={}", channelLabel(channel), priorSessionId, cause)
          val next = state.copy(channels = state.channels.updated(channel, Active(rs)))
          (next, Vector(ResumeKeepalive(rs.runtime)))
        case Some(Closing(id, _)) if id == priorSessionId =>
          onCloseCompleted(state, channel, priorSessionId, timedOut = false)
        case _ =>
          log.debug("[session] ignore late replace close failure channel={} sessionId={}", channelLabel(channel), priorSessionId)
          (state, Vector.empty)
      }

    private def onReplaceCloseCompleted(
      state: ManagerState
    , channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , result: CloseStepResult
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Replacing(rs, prior, ClosingPrior, _, aid, ks)) if prior == priorSessionId && aid == attemptId =>
          result match {
            case CloseOk =>
              onReplaceCloseSucceeded(state, channel, priorSessionId, attemptId, Some(replyTo), ClosingPrior, rs, aid, ks)
            case CloseFailed(cause) =>
              onReplaceCloseFailed(state, channel, priorSessionId, attemptId, Some(replyTo), cause)
            case CloseTimedOut =>
              val ex = Error.ReplaceTimedOut(channelLabel(channel))
              log.warn("[session] replace close timed out channel={} sessionId={} clients={}", channelLabel(channel), priorSessionId, rs.attachments.size)
              val next = state.copy(channels = state.channels.updated(channel, Replacing(rs, priorSessionId, WaitingForLateClose, None, aid, None)))
              (next, Vector(AbortReplace(ks), ReplyReplace(replyTo, ReplaceFailed(ex))))
          }
        case Some(Closing(id, _)) if id == priorSessionId =>
          result match {
            case CloseOk => onCloseCompleted(state, channel, priorSessionId, timedOut = false)
            case _ => (state, Vector.empty)
          }
        case Some(Replacing(_, prior, _, _, aid, ks)) if prior == priorSessionId && aid == attemptId =>
          log.warn("[session] replace close completed after state change channel={} sessionId={}", channelLabel(channel), priorSessionId)
          (state, Vector(AbortReplace(ks), ReplyReplace(replyTo, ReplaceFailed(Error.ReplaceStateChanged(channelLabel(channel))))))
        case _ =>
          log.warn("[session] replace close completed after state change channel={} sessionId={}", channelLabel(channel), priorSessionId)
          (state, Vector(ReplyReplace(replyTo, ReplaceFailed(Error.ReplaceStateChanged(channelLabel(channel))))))
      }

    private def onReplaceLateCloseCompleted(
      state: ManagerState
    , channel: ChannelKey
    , priorSessionId: SessionId
    , result: Either[Throwable, Unit]
    ): (ManagerState, Vector[Effect]) =
      result match {
        case Right(_) =>
          state.channels.get(channel) match {
            case Some(Replacing(rs, prior, phase, replyTo, aid, ks)) if prior == priorSessionId =>
              onReplaceCloseSucceeded(state, channel, priorSessionId, aid, replyTo, phase, rs, aid, ks)
            case Some(Closing(id, _)) if id == priorSessionId =>
              onCloseCompleted(state, channel, priorSessionId, timedOut = false)
            case _ =>
              log.debug("[session] ignore late replace close channel={} sessionId={}", channelLabel(channel), priorSessionId)
              (state, Vector.empty)
          }
        case Left(cause) =>
          onReplaceCloseFailed(state, channel, priorSessionId, new UUID(0, 0), None, cause)
      }

    private def onReplaceOpenOk(
      state: ManagerState
    , channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , session: PlayerSession
    , settings: Settings
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Replacing(rs, prior, OpeningNext, _, aid, ks)) if prior == priorSessionId && aid == attemptId =>
          val indexed = state.copy(sessionIndex = state.sessionIndex.updated(session.sessionId, channel))
          val nextRuntime = rs.runtime.copy(sessionId = session.sessionId)
          val waiters = rs.queued
          val emptied = rs.copy(runtime = nextRuntime, queued = Vector.empty)
          val (activeRs, grantEffects) = grantAll(channel, emptied, waiters, shared = true, indexed.channels.size, settings)
          val next = indexed.copy(channels = indexed.channels.updated(channel, Active(activeRs)))
          log.info(
            "[session] replaced channel={} prior={} next={} attempt={} clients={} queuedGranted={}"
          , channelLabel(channel), priorSessionId, session.sessionId, shortId(attemptId), activeRs.attachments.size, waiters.size
          )
          (next, AbortReplace(ks) +: (grantEffects :+ ReplyReplace(replyTo, Replaced(session))))
        case Some(Replacing(_, prior, OpeningNext, _, aid, _)) if prior == priorSessionId && aid != attemptId =>
          log.warn("[session] stale replace open attempt channel={} staleAttempt={} currentAttempt={}", channelLabel(channel), shortId(attemptId), shortId(aid))
          (state, Vector(CloseOrphan(channel, session.sessionId)))
        case Some(Replacing(_, prior, phase, _, _, _)) if prior == priorSessionId && phase != OpeningNext =>
          log.warn("[session] late replace open after timeout channel={} sessionId={}", channelLabel(channel), session.sessionId)
          (state, Vector(CloseOrphan(channel, session.sessionId)))
        case _ =>
          (state, Vector(CloseOrphan(channel, session.sessionId), ReplyReplace(replyTo, ReplaceFailed(Error.ReplaceStateChanged(channelLabel(channel))))))
      }

    private def onReplaceOpenFailed(
      state: ManagerState
    , channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , cause: Throwable
    ): (ManagerState, Vector[Effect]) =
      state.channels.get(channel) match {
        case Some(Replacing(rs, prior, OpeningNext, _, aid, ks)) if prior == priorSessionId && aid == attemptId =>
          val rootCause = cause match {
            case Error.ReplaceOpenFailed(_, nested) => nested
            case other => other
          }
          val next = state.copy(channels = state.channels.updated(channel, Replacing(rs, priorSessionId, ReadyToRetryOpen, None, aid, None)))
          cause match {
            case ex: Error.ReplaceTimedOut =>
              log.warn("[session] replace open timed out channel={} sessionId={} clients={}", channelLabel(channel), priorSessionId, rs.attachments.size)
              (next, Vector(AbortReplace(ks), ReplyReplace(replyTo, ReplaceFailed(ex))))
            case ex =>
              val replyEffects = Vector(AbortReplace(ks), ReplyReplace(replyTo, ReplaceFailed(ex)))
              if (isNoTuners(rootCause)) {
                log.warn("[session] replace open no tuners channel={} prior={} clients={}", channelLabel(channel), priorSessionId, rs.attachments.size)
                (next, replyEffects :+ RefreshTuners(next.cachedTuners))
              } else {
                log.warn("[session] replace open failed channel={} prior={} clients={}", channelLabel(channel), priorSessionId, rs.attachments.size, ex)
                (next, replyEffects)
              }
          }
        case Some(Replacing(_, prior, OpeningNext, _, aid, _)) if prior == priorSessionId && aid != attemptId =>
          log.warn("[session] stale replace open attempt channel={} staleAttempt={} currentAttempt={}", channelLabel(channel), shortId(attemptId), shortId(aid))
          (state, Vector.empty)
        case Some(Replacing(_, prior, phase, _, _, _)) if prior == priorSessionId && phase != OpeningNext =>
          log.debug("[session] ignore late replace open failure channel={} sessionId={} phase={}", channelLabel(channel), priorSessionId, phase)
          (state, Vector.empty)
        case _ =>
          (state, Vector(ReplyReplace(replyTo, ReplaceFailed(Error.ReplaceStateChanged(channelLabel(channel))))))
      }

    private def onReplaceOpenCompleted(
      state: ManagerState
    , channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , result: OpenStepResult
    , settings: Settings
    ): (ManagerState, Vector[Effect]) =
      result match {
        case OpenOk(session) => onReplaceOpenOk(state, channel, priorSessionId, attemptId, replyTo, session, settings)
        case OpenFailed(cause) => onReplaceOpenFailed(state, channel, priorSessionId, attemptId, replyTo, cause)
      }
  }

  private sealed trait TimerKey
  private final case class MaterializeKey(id: UUID) extends TimerKey
  private case object StartupKey extends TimerKey

  def apply(
    backend: SessionBackend
  , runtimeFactory: RuntimeFactory
  , settings: Settings = Settings()
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val queueRef = new AtomicReference[SourceQueueWithComplete[Router.ManagerEvent]]()
        val offer: Router.ManagerEvent => Unit = { event =>
          Option(queueRef.get()).foreach { queue =>
            queue.offer(event).onComplete {
              case Success(QueueOfferResult.Enqueued) => ()
              case Success(other) =>
                log.error("[session] event queue offer rejected result={}", other)
              case Failure(ex) =>
                log.error("[session] event queue offer failed", ex)
            }(context.executionContext)
          }
        }
        val runner = EffectRunner(context, timers, backend, runtimeFactory, settings, offer)
        implicit val mat: Materializer = SystemMaterializer(context.system).materializer
        implicit val ec: ExecutionContext = context.executionContext
        val (queue, _) = Source.queue[Router.ManagerEvent](256, OverflowStrategy.fail)
          .statefulMapConcat { () =>
            var state = Router.initialState(backend.totalTuners)
            event => {
              val out = scala.collection.mutable.ArrayBuffer.empty[Router.Effect]
              def handle(ev: Router.ManagerEvent): Unit = {
                val (next, effects) = Router.decide(state, ev, settings)
                state = next
                effects.foreach {
                  case start: Router.StartRuntime =>
                    val runtime = runner.startRuntime(start)
                    handle(Router.RuntimeStarted(start.channel, runtime, start.waiters))
                  case other =>
                    out += other
                }
              }
              handle(event)
              out.toVector
            }
          }
          .mapAsyncUnordered(math.max(4, backend.totalTuners))(runner.run)
          .toMat(Sink.ignore)(Keep.both)
          .run()
        queueRef.set(queue)
        timers.startSingleTimer(StartupKey, TunersUpdated(backend.totalTuners), settings.startupRefreshTimeout)
        backend.refreshTuners().onComplete {
          case Success(tuners) => offer(Router.TunersRefreshed(tuners))
          case Failure(ex) =>
            log.warn("[session] initial tuners refresh failed", ex)
            offer(Router.TunersRefreshed(backend.totalTuners))
        }
        Behaviors.receiveMessage[Command] {
          case Acquire(channel, replyTo) =>
            offer(Router.AcquireRequested(channel, replyTo))
            Behaviors.same
          case Replace(channel, priorSessionId, replyTo) =>
            offer(Router.ReplaceRequested(channel, priorSessionId, replyTo))
            Behaviors.same
          case TunersUpdated(count) =>
            offer(Router.TunersRefreshed(count))
            Behaviors.same
          case AttachmentSignal(attachmentId, event) =>
            offer(Router.AttachmentObserved(attachmentId, event))
            Behaviors.same
        }.receiveSignal {
          case (_, PostStop) =>
            queue.complete()
            Behaviors.same
        }
      }
    }

  private final case class EffectRunner(
    context: ActorContext[Command]
  , timers: TimerScheduler[Command]
  , backend: SessionBackend
  , runtimeFactory: RuntimeFactory
  , settings: Settings
  , offer: Router.ManagerEvent => Unit
  ) {
    private implicit val ec: ExecutionContext = context.executionContext
    private implicit val mat: Materializer = SystemMaterializer(context.system).materializer

    def startRuntime(effect: Router.StartRuntime): SessionRuntime =
      runtimeFactory.start(
        effect.channel
      , effect.session
      , cause => offer(Router.UpstreamDied(effect.channel, cause))
      , (priorId, replyTo) => offer(Router.ReplaceRequested(effect.channel, priorId, replyTo))
      , updated => offer(Router.SessionIdChanged(effect.channel, updated))
      , settings.backpressureTimeout
      , settings.replaceTimeout * 2 + settings.replaceTimeout / 5
      )

    def run(effect: Router.Effect): Future[Done] = {
      import Router._
      effect match {
        case ReplyAcquire(replyTo, result) =>
          replyTo ! result
          Future.successful(Done)
        case ReplyReplace(replyTo, result) =>
          replyTo ! result
          Future.successful(Done)
        case RunOpen(channel, reservationId) =>
          runOpenFlow(channel, reservationId)
          Future.successful(Done)
        case RunClose(channel, sessionId, timeout) =>
          runCloseFlow(channel, sessionId, timeout)
          Future.successful(Done)
        case RunReplaceClose(channel, priorSessionId, attemptId, replyTo) =>
          runReplaceClose(channel, priorSessionId, attemptId, replyTo)
          Future.successful(Done)
        case RunReplaceOpen(channel, priorSessionId, attemptId, replyTo, killSwitch) =>
          runReplaceOpen(channel, priorSessionId, attemptId, replyTo, killSwitch)
          Future.successful(Done)
        case AwaitCloseTimeout(channel, sessionId, timeout) =>
          runAwaitCloseTimeout(channel, sessionId, timeout)
          Future.successful(Done)
        case _: StartRuntime =>
          Future.successful(Done)
        case StopRuntime(runtime) =>
          try runtime.stop()
          catch {
            case ex: Throwable =>
              log.warn("[session] stop failed sessionId={}", runtime.sessionId, ex)
          }
          Future.successful(Done)
        case AbortReplace(killSwitch) =>
          killSwitch.foreach(_.shutdown())
          Future.successful(Done)
        case GrantAttachment(_, attachmentId, killSwitch, hubSource, replyTo, materializeTimeout) =>
          timers.startSingleTimer(
            MaterializeKey(attachmentId)
          , AttachmentSignal(attachmentId, AttachmentMaterializeTimedOut)
          , materializeTimeout
          )
          val source = wrapSource(attachmentId, hubSource, killSwitch)
          replyTo ! Attached(attachmentId, source)
          Future.successful(Done)
        case CancelMaterialize(attachmentId) =>
          timers.cancel(MaterializeKey(attachmentId))
          Future.successful(Done)
        case RefreshTuners(fallback) =>
          backend.refreshTuners().onComplete {
            case Success(tuners) => offer(TunersRefreshed(tuners))
            case Failure(ex) =>
              log.warn("[session] tuners refresh failed", ex)
              offer(TunersRefreshed(fallback))
          }
          Future.successful(Done)
        case ResumeKeepalive(runtime) =>
          runtime.resumeKeepalive()
          Future.successful(Done)
        case CloseOrphan(channel, sessionId) =>
          log.warn("[session] closing orphan session channel={} sessionId={}", Router.channelLabel(channel), sessionId)
          runCloseFlow(channel, sessionId, settings.closeTimeout)
          Future.successful(Done)
        case CancelStartupTimer =>
          timers.cancel(StartupKey)
          Future.successful(Done)
      }
    }

    private def wrapSource(
      attachmentId: UUID
    , hub: Source[ByteString, NotUsed]
    , killSwitch: SharedKillSwitch
    ): Source[ByteString, NotUsed] =
      hub
        .via(killSwitch.flow)
        .watchTermination() { (_, done) =>
          offer(Router.AttachmentObserved(attachmentId, AttachmentStarted))
          done.onComplete {
            case Success(_) => offer(Router.AttachmentObserved(attachmentId, AttachmentEnded(None)))
            case Failure(ex) => offer(Router.AttachmentObserved(attachmentId, AttachmentEnded(Some(ex))))
          }
          NotUsed
        }

    private def runOpenFlow(channel: ChannelKey, reservationId: UUID): Unit =
      ProtocolFlows.open(
        backend
      , channel
      , settings.openTimeout
      , Router.channelLabel(channel)
      , session => offer(Router.OpenCompleted(channel, reservationId, Right(session)))
      )
        .runWith(Sink.head)
        .onComplete {
          case Success(result) => offer(Router.OpenCompleted(channel, reservationId, result))
          case Failure(ex) => offer(Router.OpenCompleted(channel, reservationId, Left(ex)))
        }

    private def runCloseFlow(channel: ChannelKey, sessionId: SessionId, timeout: FiniteDuration): Unit =
      ProtocolFlows.close(backend, Router.channelLabel(channel), sessionId, timeout)
        .runWith(Sink.head)
        .onComplete {
          case Success(timedOut) => offer(Router.CloseCompleted(channel, sessionId, timedOut))
          case Failure(ex) =>
            log.warn("[session] close flow failed channel={} sessionId={}", Router.channelLabel(channel), sessionId, ex)
            offer(Router.CloseCompleted(channel, sessionId, timedOut = false))
        }

    private def runAwaitCloseTimeout(channel: ChannelKey, sessionId: SessionId, timeout: FiniteDuration): Unit =
      ProtocolFlows.awaitTimeout(timeout)
        .runWith(Sink.head)
        .onComplete { _ =>
          offer(Router.CloseCompleted(channel, sessionId, timedOut = true))
        }

    private def runReplaceClose(
      channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    ): Unit =
      ProtocolFlows.closeStep(
        backend
      , priorSessionId
      , settings.replaceTimeout
      , result => offer(Router.ReplaceLateCloseCompleted(channel, priorSessionId, result.toEither))
      )
        .runWith(Sink.head)
        .onComplete {
          case Success(result) =>
            offer(Router.ReplaceCloseCompleted(channel, priorSessionId, attemptId, replyTo, result))
          case Failure(ex) =>
            log.warn(
              "[session] replace close flow failed channel={} sessionId={} attempt={}"
            , Router.channelLabel(channel), priorSessionId, attemptId.toString.take(8), ex
            )
            offer(Router.ReplaceCloseCompleted(channel, priorSessionId, attemptId, replyTo, CloseFailed(ex)))
        }

    private def runReplaceOpen(
      channel: ChannelKey
    , priorSessionId: SessionId
    , attemptId: UUID
    , replyTo: ActorRef[ReplaceResult]
    , killSwitch: SharedKillSwitch
    ): Unit =
      ProtocolFlows.openStep(
        backend
      , channel
      , Router.channelLabel(channel)
      , settings.replaceTimeout
      , session =>
          offer(Router.ReplaceOpenCompleted(channel, priorSessionId, attemptId, replyTo, OpenOk(session)))
      )
        .via(killSwitch.flow)
        .runWith(Sink.head)
        .onComplete {
          case Success(result) =>
            offer(Router.ReplaceOpenCompleted(channel, priorSessionId, attemptId, replyTo, result))
          case Failure(ex) =>
            log.warn(
              "[session] replace open flow failed channel={} sessionId={} attempt={}"
            , Router.channelLabel(channel), priorSessionId, attemptId.toString.take(8), ex
            )
            offer(Router.ReplaceOpenCompleted(channel, priorSessionId, attemptId, replyTo, OpenFailed(ex)))
        }
  }
}
