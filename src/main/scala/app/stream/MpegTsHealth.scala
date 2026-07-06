package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic}
import org.apache.pekko.util.ByteString

import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.compiletime.uninitialized
import scala.concurrent.duration._

object MpegTsHealth {
  val log = LoggerFactory.getLogger(this.getClass)
  val PacketSize = 188
  val NullPid = 0x1FFF

  final case class Settings(
    windowSec: Int
  , ccMax: Int
  , syncMax: Int
  , nullRatioMax: Double
  , enforce: Boolean
  )

  def monitor(s: Settings): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new Stage(s))

  private final class Stage(s: Settings) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("MpegTsHealth.in")
    val out: Outlet[ByteString] = Outlet("MpegTsHealth.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private var carry: ByteString = ByteString.empty
      private var syncLoss = 0
      private var ccErrors = 0
      private var nullPackets = 0
      private var totalPackets = 0
      private var warnedDegraded = false
      private val prevCc = mutable.HashMap.empty[Int, Int]
      private var failAsync: org.apache.pekko.stream.stage.AsyncCallback[Throwable] = uninitialized

      override def preStart(): Unit = {
        failAsync = getAsyncCallback(failStage)
        scheduleWithFixedDelay("roll", s.windowSec.seconds, s.windowSec.seconds)
      }

      override protected def onTimer(timerKey: Any): Unit = {
        val (degraded, detail) = degradedSnapshot()
        if (degraded) {
          if (!warnedDegraded) {
            log.warn("[stream:hls] ts health degraded {}", detail)
            warnedDegraded = true
          }
          if (s.enforce) {
            failAsync.invoke(HlsBackend.HlsError.TsHealthDegraded(detail))
          }
        } else {
          warnedDegraded = false
        }
        syncLoss = 0
        ccErrors = 0
        nullPackets = 0
        totalPackets = 0
      }

      private def degradedSnapshot(): (Boolean, String) = {
        val degraded =
          ccErrors > s.ccMax ||
          syncLoss > s.syncMax ||
          (totalPackets > 0 && nullPackets.toDouble / totalPackets > s.nullRatioMax)
        val detail =
          s"syncLoss=$syncLoss ccErrors=$ccErrors nullPackets=$nullPackets totalPackets=$totalPackets"
        (degraded, detail)
      }

      private def pidAt(arr: Array[Byte], offset: Int): Int =
        ((arr(offset + 1) & 0x1F) << 8) | (arr(offset + 2) & 0xFF)

      private def processPacket(arr: Array[Byte], offset: Int): Unit = {
        totalPackets += 1
        val pid = pidAt(arr, offset)
        if (pid == NullPid) {
          nullPackets += 1
        }
        val afc = arr(offset + 3) & 0x30
        val hasPayload = (afc & 0x10) != 0
        if (hasPayload) {
          val cc = arr(offset + 3) & 0x0F
          prevCc.get(pid) match {
            case Some(prev) if cc != prev && cc != ((prev + 1) & 0x0F) =>
              ccErrors += 1
            case _ => ()
          }
          prevCc(pid) = cc
        }
      }

      private def parseForMetrics(buf: Array[Byte], length: Int): Int = {
        var pos = 0
        while (pos + PacketSize <= length) {
          if (buf(pos) == 0x47.toByte) {
            processPacket(buf, pos)
            pos += PacketSize
          } else {
            syncLoss += 1
            var found = -1
            var scan = pos + 1
            while (scan + PacketSize <= length && found < 0) {
              if (buf(scan) == 0x47.toByte) {
                found = scan
              } else {
                scan += 1
              }
            }
            if (found < 0) {
              pos = length
            } else {
              pos = found
            }
          }
        }
        pos
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val incoming = grab(in)
          val combined = carry ++ incoming
          val arr = combined.toArray
          val consumed = parseForMetrics(arr, arr.length)
          carry = combined.drop(consumed)
          push(out, incoming)
        }

        override def onUpstreamFinish(): Unit = {
          val (degraded, detail) = degradedSnapshot()
          if (degraded && !warnedDegraded) {
            log.warn("[stream:hls] ts health degraded {}", detail)
          }
          if (s.enforce && degraded) {
            failAsync.invoke(HlsBackend.HlsError.TsHealthDegraded(detail))
          } else {
            if (carry.nonEmpty) {
              emit(out, carry)
            }
            completeStage()
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }
}