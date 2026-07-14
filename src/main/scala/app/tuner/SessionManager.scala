package app.tuner

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.typed.{ActorRef, Behavior}
import pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
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
  , replyTo: ActorRef[ReplaceResult]
  , result: Either[Throwable, PlayerSession]
  ) extends Command

  case object NoAvailableTunersError extends Exception("No available tuners")

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
  )

  trait RuntimeFactory {
    def start(
      channel: ChannelKey
    , session: PlayerSession
    , onTerminated: Option[Throwable] => Unit
    , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
    , backpressureTimeout: FiniteDuration
    ): SessionRuntime
  }

  private final case class PendingAcquire(replyTo: ActorRef[AcquireResult])

  private sealed trait AttachmentState
  private case object Granted extends AttachmentState
  private case object Materialized extends AttachmentState

  private final case class SessionRuntimeState(
    runtime: SessionRuntime
  , attachments: Map[UUID, AttachmentState]
  , queued: Vector[PendingAcquire] = Vector.empty
  )

  private sealed trait SessionEntry
  private final case class Opening(
    reservationId: UUID
  , waiters: Vector[PendingAcquire]
  ) extends SessionEntry
  private final case class Active(state: SessionRuntimeState) extends SessionEntry
  private final case class Replacing(state: SessionRuntimeState) extends SessionEntry
  private final case class Closing(sessionId: SessionId) extends SessionEntry

  private sealed trait TimerKey
  private final case class MaterializeKey(id: UUID) extends TimerKey
  private final case class OpenKey(channel: ChannelKey, reservationId: UUID) extends TimerKey
  private final case class CloseKey(channel: ChannelKey, sessionId: SessionId) extends TimerKey

  def apply(
    backend: SessionBackend
  , runtimeFactory: RuntimeFactory
  , settings: Settings = Settings()
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new Manager(context, timers, backend, runtimeFactory, settings).behavior()
      }
    }

  private final class Manager(
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

    def behavior(): Behavior[Command] =
      Behaviors.receiveMessage {
        case Acquire(channel, replyTo) =>
          onAcquire(channel, replyTo)
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
        case ReplaceOpened(channel, priorSessionId, replyTo, result) =>
          onReplaceOpened(channel, priorSessionId, replyTo, result)
          Behaviors.same
        case CloseCompleted(channel, sessionId) =>
          onCloseCompleted(channel, sessionId)
          Behaviors.same
        case CloseTimedOut(channel, sessionId) =>
          onCloseTimedOut(channel, sessionId)
          Behaviors.same
      }

    private def reservations: Int = channels.size

    private def ch(channel: ChannelKey): String =
      channel match {
        case Gen4Channel(id) => id
        case LegacyChannel(id) => id.toString
      }

    private def shortId(id: UUID): String = id.toString.take(8)

    private def clientCount(state: SessionRuntimeState): Int = state.attachments.size

    private def onAcquire(channel: ChannelKey, replyTo: ActorRef[AcquireResult]): Unit =
      channels.get(channel) match {
        case Some(Opening(reservationId, waiters)) =>
          val pending = waiters.size + 1
          channels = channels.updated(channel, Opening(reservationId, waiters :+ PendingAcquire(replyTo)))
          log.info(
            "[session] client queue channel={} state=opening pending={} reserved={} total={}"
          , ch(channel)
          , pending
          , reservations
          , cachedTuners
          )
        case Some(Active(state)) =>
          replyAttached(channel, state, replyTo, shared = true, Active.apply)
        case Some(Replacing(state)) =>
          val pending = state.queued.size + 1
          channels = channels.updated(
            channel
          , Replacing(state.copy(queued = state.queued :+ PendingAcquire(replyTo)))
          )
          log.info(
            "[session] client queue channel={} sessionId={} state=replacing pending={} clients={}"
          , ch(channel)
          , state.runtime.sessionId
          , pending
          , clientCount(state)
          )
        case Some(Closing(sessionId)) =>
          log.warn(
            "[session] reject channel={} sessionId={} state=closing reserved={} total={}"
          , ch(channel)
          , sessionId
          , reservations
          , cachedTuners
          )
          replyTo ! NoAvailableTuners
        case None =>
          if (reservations >= cachedTuners) {
            log.warn(
              "[session] no available tuners channel={} reserved={} total={}"
            , ch(channel)
            , reservations
            , cachedTuners
            )
            val _ = backend.refreshTuners().map { tuners =>
              cachedTuners = tuners
            }
            replyTo ! NoAvailableTuners
          } else {
            startOpening(channel, replyTo)
          }
      }

    private def startOpening(channel: ChannelKey, replyTo: ActorRef[AcquireResult]): Unit = {
      val reservationId = UUID.randomUUID()
      channels = channels.updated(channel, Opening(reservationId, Vector(PendingAcquire(replyTo))))
      timers.startSingleTimer(OpenKey(channel, reservationId), OpenTimedOut(channel, reservationId), settings.openTimeout)
      log.info(
        "[session] opening channel={} reservation={} reserved={} total={}"
      , ch(channel)
      , shortId(reservationId)
      , reservations
      , cachedTuners
      )
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
              log.warn(
                "[session] open failed channel={} reservation={} waiters={} reserved={}"
              , ch(channel)
              , shortId(reservationId)
              , waiters.size
              , reservations
              , ex
              )
              failWaiters(channel, waiters, ex)
              if (isNoTuners(ex)) {
                val _ = backend.refreshTuners().map { tuners => cachedTuners = tuners }
              }
          }
        case Some(Opening(id, _)) =>
          log.debug(
            "[session] ignore stale open result channel={} expected={} actual={}"
          , ch(channel)
          , shortId(reservationId)
          , shortId(id)
          )
        case _ =>
          result match {
            case Right(session) =>
              log.warn(
                "[session] open completed after cancel channel={} sessionId={}, closing orphan"
              , ch(channel)
              , session.sessionId
              )
              context.pipeToSelf(backend.close(session.sessionId)) {
                case Success(_) => CloseCompleted(channel, session.sessionId)
                case Failure(ex) =>
                  log.warn(
                    "[session] orphan close failed channel={} sessionId={}"
                  , ch(channel)
                  , session.sessionId
                  , ex
                  )
                  CloseCompleted(channel, session.sessionId)
              }
            case Left(ex) =>
              log.debug("[session] ignore late open failure channel={}", ch(channel), ex)
          }
      }

    private def onOpenTimedOut(channel: ChannelKey, reservationId: UUID): Unit =
      channels.get(channel) match {
        case Some(Opening(id, waiters)) if id == reservationId =>
          channels = channels - channel
          val ex = new java.util.concurrent.TimeoutException(s"open timed out for ${ch(channel)}")
          log.warn(
            "[session] open timed out channel={} reservation={} waiters={} reserved={}"
          , ch(channel)
          , shortId(reservationId)
          , waiters.size
          , reservations
          )
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
      , settings.backpressureTimeout
      )
      sessionIndex = sessionIndex.updated(session.sessionId, channel)
      var state = SessionRuntimeState(runtime, Map.empty)
      waiters.foreach { waiter =>
        state = grantAttachment(channel, state, waiter.replyTo, shared = waiters.size > 1)
      }
      channels = channels.updated(channel, Active(state))
      log.info(
        "[session] active channel={} sessionId={} clients={} reserved={} total={}"
      , ch(channel)
      , session.sessionId
      , clientCount(state)
      , reservations
      , cachedTuners
      )
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
      val source = wrapSource(attachmentId, state.runtime.hubSource)
      timers.startSingleTimer(
        MaterializeKey(attachmentId)
      , AttachmentMaterializeTimeout(attachmentId)
      , settings.materializationTimeout
      )
      replyTo ! Attached(attachmentId, source)
      val next = state.copy(attachments = state.attachments.updated(attachmentId, Granted))
      log.info(
        "[session] client grant channel={} sessionId={} attachment={} shared={} clients={} reserved={}"
      , ch(channel)
      , state.runtime.sessionId
      , shortId(attachmentId)
      , shared
      , clientCount(next)
      , reservations
      )
      next
    }

    private def wrapSource(
      attachmentId: UUID
    , hub: Source[ByteString, NotUsed]
    ): Source[ByteString, NotUsed] =
      hub
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
          case Some(Granted) =>
            timers.cancel(MaterializeKey(attachmentId))
            val next = state.copy(attachments = state.attachments.updated(attachmentId, Materialized))
            channels = channels.updated(channel, updateEntry(entry, next))
            log.info(
              "[session] client connect channel={} sessionId={} attachment={} clients={} shared={}"
            , ch(channel)
            , state.runtime.sessionId
            , shortId(attachmentId)
            , clientCount(next)
            , clientCount(next) > 1
            )
          case Some(Materialized) =>
            log.debug(
              "[session] ignore duplicate connect channel={} attachment={}"
            , ch(channel)
            , shortId(attachmentId)
            )
          case None => ()
        }
      }
    }

    private def onAttachmentEnded(attachmentId: UUID, cause: Option[Throwable]): Unit = {
      timers.cancel(MaterializeKey(attachmentId))
      findAttachment(attachmentId).foreach { case (channel, entry, state) =>
        val next = state.copy(attachments = state.attachments - attachmentId)
        val remaining = clientCount(next)
        cause match {
          case None =>
            log.info(
              "[session] client disconnect channel={} sessionId={} attachment={} clientsRemaining={} shared={}"
            , ch(channel)
            , state.runtime.sessionId
            , shortId(attachmentId)
            , remaining
            , remaining > 1
            )
          case Some(ex) =>
            log.warn(
              "[session] client stream failed channel={} sessionId={} attachment={} clientsRemaining={}"
            , ch(channel)
            , state.runtime.sessionId
            , shortId(attachmentId)
            , remaining
            , ex
            )
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
          case Some(Granted) =>
            val next = state.copy(attachments = state.attachments - attachmentId)
            log.warn(
              "[session] client materialize timeout channel={} sessionId={} attachment={} clientsRemaining={}"
            , ch(channel)
            , state.runtime.sessionId
            , shortId(attachmentId)
            , clientCount(next)
            )
            if (next.attachments.isEmpty && next.queued.isEmpty)
              beginClosing(channel, next.runtime)
            else
              channels = channels.updated(channel, updateEntry(entry, next))
          case _ => ()
        }
      }

    private def beginClosing(channel: ChannelKey, runtime: SessionRuntime): Unit = {
      val sessionId = runtime.sessionId
      channels = channels.updated(channel, Closing(sessionId))
      try runtime.stop()
      catch {
        case ex: Throwable =>
          log.warn("[session] stop failed channel={} sessionId={}", ch(channel), sessionId, ex)
      }
      timers.startSingleTimer(CloseKey(channel, sessionId), CloseTimedOut(channel, sessionId), settings.closeTimeout)
      log.info(
        "[session] closing channel={} sessionId={} reserved={} total={}"
      , ch(channel)
      , sessionId
      , reservations
      , cachedTuners
      )
      context.pipeToSelf(backend.close(sessionId)) {
        case Success(_) => CloseCompleted(channel, sessionId)
        case Failure(ex) =>
          log.warn("[session] close failed channel={} sessionId={}", ch(channel), sessionId, ex)
          CloseCompleted(channel, sessionId)
      }
    }

    private def onCloseCompleted(channel: ChannelKey, sessionId: SessionId): Unit =
      channels.get(channel) match {
        case Some(Closing(id)) if id == sessionId =>
          timers.cancel(CloseKey(channel, sessionId))
          sessionIndex = sessionIndex - sessionId
          channels = channels - channel
          log.info(
            "[session] closed channel={} sessionId={} reserved={} total={}"
          , ch(channel)
          , sessionId
          , reservations
          , cachedTuners
          )
        case _ =>
          sessionIndex = sessionIndex - sessionId
      }

    private def onCloseTimedOut(channel: ChannelKey, sessionId: SessionId): Unit =
      channels.get(channel) match {
        case Some(Closing(id)) if id == sessionId =>
          sessionIndex = sessionIndex - sessionId
          channels = channels - channel
          log.warn(
            "[session] close timed out channel={} sessionId={} reserved={}"
          , ch(channel)
          , sessionId
          , reservations
          )
        case _ => ()
      }

    private def onUpstreamTerminated(channel: ChannelKey, cause: Option[Throwable]): Unit =
      channels.get(channel) match {
        case Some(Active(state)) =>
          cause match {
            case Some(ex) =>
              log.warn(
                "[session] upstream failed channel={} sessionId={} clients={}"
              , ch(channel)
              , state.runtime.sessionId
              , clientCount(state)
              , ex
              )
            case None =>
              log.info(
                "[session] upstream complete channel={} sessionId={} clients={}"
              , ch(channel)
              , state.runtime.sessionId
              , clientCount(state)
              )
          }
          failQueued(channel, state.queued, cause.getOrElse(new RuntimeException("upstream terminated")))
          beginClosing(channel, state.runtime)
        case Some(Replacing(state)) =>
          cause match {
            case Some(ex) =>
              log.warn(
                "[session] upstream failed during replace channel={} sessionId={} clients={}"
              , ch(channel)
              , state.runtime.sessionId
              , clientCount(state)
              , ex
              )
            case None =>
              log.info(
                "[session] upstream complete during replace channel={} sessionId={} clients={}"
              , ch(channel)
              , state.runtime.sessionId
              , clientCount(state)
              )
          }
          failQueued(channel, state.queued, cause.getOrElse(new RuntimeException("upstream terminated")))
          beginClosing(channel, state.runtime)
        case _ =>
          cause.foreach { ex =>
            log.debug("[session] ignore upstream terminate channel={} state=absent", ch(channel), ex)
          }
      }

    private def onReplace(
      channel: ChannelKey
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    ): Unit =
      channels.get(channel) match {
        case Some(Active(state)) if state.runtime.sessionId == priorSessionId =>
          channels = channels.updated(channel, Replacing(state))
          log.info(
            "[session] replacing channel={} sessionId={} clients={}"
          , ch(channel)
          , priorSessionId
          , clientCount(state)
          )
          context.pipeToSelf(backend.close(priorSessionId)) {
            case Success(_) => ReplaceClosed(channel, priorSessionId, replyTo)
            case Failure(ex) =>
              log.warn(
                "[session] replace close failed channel={} sessionId={}"
              , ch(channel)
              , priorSessionId
              , ex
              )
              ReplaceClosed(channel, priorSessionId, replyTo)
          }
        case Some(Replacing(state)) if state.runtime.sessionId == priorSessionId =>
          log.warn(
            "[session] replace already in progress channel={} sessionId={}"
          , ch(channel)
          , priorSessionId
          )
          replyTo ! ReplaceFailed(new IllegalStateException("replace already in progress"))
        case other =>
          log.warn(
            "[session] replace rejected channel={} sessionId={} state={}"
          , ch(channel)
          , priorSessionId
          , other.map(_.getClass.getSimpleName).getOrElse("absent")
          )
          replyTo ! ReplaceFailed(new IllegalStateException(s"channel not active for replace: ${ch(channel)}"))
      }

    private def onReplaceClosed(
      channel: ChannelKey
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    ): Unit =
      channels.get(channel) match {
        case Some(Replacing(_)) =>
          sessionIndex = sessionIndex - priorSessionId
          log.info("[session] replace closed prior session channel={} sessionId={}", ch(channel), priorSessionId)
          context.pipeToSelf(backend.open(channel)) {
            case Success(session) => ReplaceOpened(channel, priorSessionId, replyTo, Right(session))
            case Failure(ex) => ReplaceOpened(channel, priorSessionId, replyTo, Left(ex))
          }
        case _ =>
          log.warn(
            "[session] replace close completed after state change channel={} sessionId={}"
          , ch(channel)
          , priorSessionId
          )
          replyTo ! ReplaceFailed(new IllegalStateException(s"channel left replacing state: ${ch(channel)}"))
      }

    private def onReplaceOpened(
      channel: ChannelKey
    , priorSessionId: SessionId
    , replyTo: ActorRef[ReplaceResult]
    , result: Either[Throwable, PlayerSession]
    ): Unit =
      channels.get(channel) match {
        case Some(Replacing(state)) =>
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
              log.info(
                "[session] replaced channel={} prior={} next={} clients={} queuedGranted={}"
              , ch(channel)
              , priorSessionId
              , session.sessionId
              , clientCount(activeState)
              , waiters.size
              )
            case Left(ex) =>
              replyTo ! ReplaceFailed(ex)
              if (isNoTuners(ex))
                log.warn(
                  "[session] replace open no tuners channel={} prior={} clients={}"
                , ch(channel)
                , priorSessionId
                , clientCount(state)
                )
              else
                log.warn(
                  "[session] replace open failed channel={} prior={} clients={}"
                , ch(channel)
                , priorSessionId
                , clientCount(state)
                , ex
                )
          }
        case _ =>
          result.foreach { session =>
            log.warn(
              "[session] replace opened after state change channel={} sessionId={}, closing orphan"
            , ch(channel)
            , session.sessionId
            )
            context.pipeToSelf(backend.close(session.sessionId))(_ => CloseCompleted(channel, session.sessionId))
          }
          replyTo ! ReplaceFailed(new IllegalStateException(s"channel left replacing state: ${ch(channel)}"))
      }

    private def findAttachment(
      attachmentId: UUID
    ): Option[(ChannelKey, SessionEntry, SessionRuntimeState)] =
      channels.collectFirst {
        case (channel, entry @ Active(state)) if state.attachments.contains(attachmentId) =>
          (channel, entry, state)
        case (channel, entry @ Replacing(state)) if state.attachments.contains(attachmentId) =>
          (channel, entry, state)
      }

    private def updateEntry(entry: SessionEntry, state: SessionRuntimeState): SessionEntry =
      entry match {
        case _: Active => Active(state)
        case _: Replacing => Replacing(state)
        case other => other
      }

    private def failWaiters(channel: ChannelKey, waiters: Vector[PendingAcquire], ex: Throwable): Unit = {
      if (waiters.nonEmpty)
        log.warn(
          "[session] failing waiters channel={} count={} reason={}"
        , ch(channel)
        , waiters.size
        , ex.toString
        )
      waiters.foreach { waiter =>
        if (isNoTuners(ex)) waiter.replyTo ! NoAvailableTuners
        else waiter.replyTo ! AcquireFailed(ex)
      }
    }

    private def failQueued(channel: ChannelKey, queued: Vector[PendingAcquire], ex: Throwable): Unit =
      failWaiters(channel, queued, ex)

    private def isNoTuners(ex: Throwable): Boolean =
      ex == NoAvailableTunersError ||
        ex == Tablo4thGen.Error.NoAvailableTuners ||
        ex == TabloLegacy.Channel.Response.NoAvailableTunersError ||
        Option(ex.getMessage).exists(_.toLowerCase.contains("no available tuners"))
  }
}
