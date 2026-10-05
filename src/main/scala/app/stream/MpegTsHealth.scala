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
  , teiMax: Int = 10
  )

  val NullPacketArray: Array[Byte] = {
    val arr = Array.fill[Byte](PacketSize)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x1F.toByte
    arr(2) = 0xFF.toByte
    arr(3) = 0x10.toByte // payload only, CC = 0
    arr
  }

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
      private var teiErrors = 0
      private var nullPackets = 0
      private var totalPackets = 0
      private var warnedDegraded = false
      private val prevCc = mutable.HashMap.empty[Int, Int]
      private val teiPids = mutable.HashSet.empty[Int]
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
        teiErrors = 0
        nullPackets = 0
        totalPackets = 0
      }

      private def degradedSnapshot(): (Boolean, String) = {
        val degraded =
          ccErrors > s.ccMax ||
          syncLoss > s.syncMax ||
          teiErrors > s.teiMax ||
          (totalPackets >= 10 && (nullPackets.toDouble / totalPackets > s.nullRatioMax))
        val detail =
          s"syncLoss=$syncLoss ccErrors=$ccErrors teiErrors=$teiErrors nullPackets=$nullPackets totalPackets=$totalPackets"
        (degraded, detail)
      }

      private def pidAt(arr: Array[Byte], offset: Int): Int =
        ((arr(offset + 1) & 0x1F) << 8) | (arr(offset + 2) & 0xFF)

      private def processPacket(arr: Array[Byte], offset: Int): Option[ByteString] = {
        totalPackets += 1
        val isTei = (arr(offset + 1) & 0x80) != 0
        if (isTei) {
          teiErrors += 1
          val pid = pidAt(arr, offset)
          if (pid >= 0x0010 && pid != NullPid) {
            val _ = teiPids += pid
          }
          System.arraycopy(NullPacketArray, 0, arr, offset, PacketSize)
          nullPackets += 1
          None
        } else {
          val pid = pidAt(arr, offset)
          if (pid == NullPid) {
            nullPackets += 1
          }
          val afc = (arr(offset + 3) & 0x30) >> 4
          val hasPayload = (afc & 0x01) != 0
          val hasAdaptation = (afc & 0x02) != 0
          val isDiscontinuity =
            hasAdaptation &&
            (arr(offset + 4) & 0xFF) >= 1 &&
            (arr(offset + 5) & 0x80) != 0

          var prefix: Option[ByteString] = None

          if (isDiscontinuity) {
            if (pid == NullPid) {
              val b = ByteString.newBuilder
              for (elemPid <- prevCc.keys if elemPid >= 0x0010 && elemPid != NullPid) {
                b ++= MpegTsSync.discontinuityPacket(elemPid)
              }
              prevCc.clear()
              teiPids.clear()
              val res = b.result()
              if (res.nonEmpty) {
                prefix = Some(res)
              }
            } else {
              val _ = prevCc.remove(pid)
              val _ = teiPids.remove(pid)
            }
          }

          if (hasPayload) {
            val cc = arr(offset + 3) & 0x0F
            if (!isDiscontinuity) {
              val hadTeiLoss = teiPids.remove(pid)
              val ccJump = prevCc.get(pid) match {
                case Some(prev) => cc != prev && cc != ((prev + 1) & 0x0F)
                case None => false
              }
              if (ccJump) {
                ccErrors += 1
              }
              if ((ccJump || hadTeiLoss) && pid >= 0x0010 && pid != NullPid) {
                prefix = Some(MpegTsSync.discontinuityPacket(pid))
              }
            }
            prevCc(pid) = cc
          }
          prefix
        }
      }

      private def parseForMetrics(buf: Array[Byte], length: Int): (Int, ByteString) = {
        var pos = 0
        val outBuilder = ByteString.newBuilder
        while (pos + PacketSize <= length) {
          if (buf(pos) == 0x47.toByte) {
            val prefix = processPacket(buf, pos)
            prefix.foreach(outBuilder ++= _)
            outBuilder ++= ByteString.fromArray(buf, pos, PacketSize)
            pos += PacketSize
          } else {
            syncLoss += 1
            if (prevCc.nonEmpty) {
              for (elemPid <- prevCc.keys if elemPid >= 0x0010 && elemPid != NullPid) {
                outBuilder ++= MpegTsSync.discontinuityPacket(elemPid)
              }
              outBuilder ++= MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
              prevCc.clear()
              teiPids.clear()
            }
            val found = MpegTsSync.findNextSync(buf, pos + 1, length)
            if (found < 0) {
              pos = length - PacketSize + 1
            } else {
              pos = found
            }
          }
        }
        (pos, outBuilder.result())
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val incoming = grab(in)
          val combined = carry ++ incoming
          val arr = combined.toArray
          val (consumed, output) = parseForMetrics(arr, arr.length)
          carry = combined.drop(consumed)
          val (degraded, detail) = degradedSnapshot()
          if (degraded) {
            if (!warnedDegraded) {
              log.warn("[stream:hls] ts health degraded {}", detail)
              warnedDegraded = true
            }
            if (s.enforce) {
              failStage(HlsBackend.HlsError.TsHealthDegraded(detail))
            } else {
              if (output.nonEmpty) {
                push(out, output)
              } else {
                pull(in)
              }
            }
          } else {
            if (output.nonEmpty) {
              push(out, output)
            } else {
              pull(in)
            }
          }
        }

        override def onUpstreamFinish(): Unit = {
          val (degraded, detail) = degradedSnapshot()
          if (degraded && !warnedDegraded) {
            log.warn("[stream:hls] ts health degraded {}", detail)
          }
          if (s.enforce && degraded) {
            failAsync.invoke(HlsBackend.HlsError.TsHealthDegraded(detail))
          } else {
            if (carry.length >= PacketSize) {
              val arr = carry.toArray
              val (_, output) = parseForMetrics(arr, arr.length)
              if (output.nonEmpty) {
                emit(out, output)
              }
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