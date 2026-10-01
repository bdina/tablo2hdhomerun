package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object MpegTsSync {
  val PacketSize: Int = 188
  val NullPid: Int = 0x1FFF
  val PatPid: Int = 0x0000

  // 188-byte MPEG-TS packet with sync byte 0x47, Null PID 0x1FFF, adaptation field only (0x20),
  // adaptation_field_length 183, discontinuity_indicator = 1 (0x80), and 182 0xFF stuffing bytes.
  val MPEGTS_DISCONTINUITY_PACKET: ByteString = {
    val arr = Array.fill[Byte](PacketSize)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x1F.toByte
    arr(2) = 0xFF.toByte
    arr(3) = 0x20.toByte // adaptation field only, CC = 0
    arr(4) = 183.toByte  // adaptation field length (188 - 5)
    arr(5) = 0x80.toByte // discontinuity_indicator = 1
    ByteString(arr)
  }

  // 188-byte standard MPEG-TS null/stuffing packet (ISO/IEC 13818-1) with sync byte 0x47,
  // Null PID 0x1FFF, payload only (0x10), CC = 0, and 184 0xFF payload bytes.
  val MPEGTS_NULL_PACKET: ByteString = {
    val arr = Array.fill[Byte](PacketSize)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x1F.toByte
    arr(2) = 0xFF.toByte
    arr(3) = 0x10.toByte // payload only, CC = 0
    ByteString(arr)
  }

  def preRollNullChunk(packetCount: Int = 7): ByteString = {
    val count = math.max(1, packetCount)
    val single = MPEGTS_NULL_PACKET
    val b = ByteString.newBuilder
    var i = 0
    while (i < count) {
      b ++= single
      i += 1
    }
    b.result()
  }

  def withPreRollKeepAlive(
    realSourceFuture: Future[Source[ByteString, NotUsed]]
  , interval: FiniteDuration = 100.millis
  , chunkPackets: Int = 7
  ): Source[ByteString, NotUsed] = {
    realSourceFuture.value match {
      case Some(Success(source)) => source
      case Some(Failure(ex)) => Source.failed(ex)
      case None =>
        val chunk = preRollNullChunk(chunkPackets)
        val preRollSource: Source[ByteString, NotUsed] =
          Source.tick(0.millis, interval, chunk)
            .takeWhile { _ =>
              realSourceFuture.value match {
                case None => true
                case Some(Success(_)) => false
                case Some(Failure(ex)) => throw ex
              }
            }
            .mapMaterializedValue(_ => NotUsed)

        val delayedRealSource = Source.futureSource(realSourceFuture)
        preRollSource
          .concat(Source.single(MPEGTS_DISCONTINUITY_PACKET))
          .concat(delayedRealSource)
          .via(dedupConsecutiveDiscontinuity)
    }
  }

  def dedupConsecutiveDiscontinuity: Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new DedupDiscontinuityStage)

  private final class DedupDiscontinuityStage extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("DedupDiscontinuity.in")
    val out: Outlet[ByteString] = Outlet("DedupDiscontinuity.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var lastWasDiscontinuity = false

      private def isDiscontinuity(bs: ByteString, offset: Int): Boolean =
        bs.length >= offset + PacketSize &&
        bs(offset) == 0x47.toByte &&
        bs(offset + 1) == 0x1F.toByte &&
        bs(offset + 2) == 0xFF.toByte &&
        bs(offset + 3) == 0x20.toByte &&
        bs(offset + 4) == 183.toByte &&
        (bs(offset + 5) & 0x80) != 0

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          if (elem.length < PacketSize || elem.length % PacketSize != 0) {
            push(out, elem)
          } else {
            var pos = 0
            val filtered = ByteString.newBuilder
            var droppedAny = false

            while (pos + PacketSize <= elem.length) {
              val isDiscont = isDiscontinuity(elem, pos)
              if (isDiscont) {
                if (lastWasDiscontinuity) {
                  droppedAny = true
                } else {
                  lastWasDiscontinuity = true
                  filtered ++= elem.slice(pos, pos + PacketSize)
                }
              } else {
                lastWasDiscontinuity = false
                filtered ++= elem.slice(pos, pos + PacketSize)
              }
              pos += PacketSize
            }

            if (!droppedAny) {
              push(out, elem)
            } else {
              val result = filtered.result()
              if (result.nonEmpty) {
                push(out, result)
              } else {
                pull(in)
              }
            }
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }

  final case class CachedHeaders(
    pat: Option[ByteString] = None
  , pmt: Option[ByteString] = None
  ) {
    def isEmpty: Boolean = pat.isEmpty && pmt.isEmpty

    def syncPrefix: ByteString = {
      val b = ByteString.newBuilder
      b ++= MPEGTS_DISCONTINUITY_PACKET
      pat.foreach(b ++= _)
      pmt.foreach(b ++= _)
      b.result()
    }
  }

  def extractPmtPid(packet: Array[Byte], offset: Int): Option[Int] = {
    val pusi = (packet(offset + 1) & 0x40) != 0
    val afc = (packet(offset + 3) & 0x30) >> 4
    val payloadOffset =
      if (afc == 1) offset + 4
      else if (afc == 3) offset + 5 + (packet(offset + 4) & 0xFF)
      else offset + PacketSize

    if (payloadOffset < offset + PacketSize) {
      val pointerField = if (pusi) packet(payloadOffset) & 0xFF else 0
      val tableOffset = payloadOffset + (if (pusi) 1 + pointerField else 0)
      if (tableOffset + 8 < offset + PacketSize) {
        val tableId = packet(tableOffset) & 0xFF
        if (tableId == 0x00) {
          val sectionLength = ((packet(tableOffset + 1) & 0x0F) << 8) | (packet(tableOffset + 2) & 0xFF)
          var entryPos = tableOffset + 8
          val endPos = math.min(offset + PacketSize, tableOffset + 3 + sectionLength - 4)
          var pmtPidOpt: Option[Int] = None
          while (entryPos + 4 <= endPos && pmtPidOpt.isEmpty) {
            val programNum = ((packet(entryPos) & 0xFF) << 8) | (packet(entryPos + 1) & 0xFF)
            val pmtPid = ((packet(entryPos + 2) & 0x1F) << 8) | (packet(entryPos + 3) & 0xFF)
            if (programNum != 0) {
              pmtPidOpt = Some(pmtPid)
            }
            entryPos += 4
          }
          pmtPidOpt
        } else None
      } else None
    } else None
  }

  def cacheFlow(
    cachedHeadersRef: AtomicReference[CachedHeaders]
  , onHeadersUpdated: CachedHeaders => Unit = _ => ()
  ): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new CacheStage(cachedHeadersRef, onHeadersUpdated))

  def primeClientSource(
    source: Source[ByteString, NotUsed]
  , cachedHeadersRef: AtomicReference[CachedHeaders]
  ): Source[ByteString, NotUsed] =
    Source.lazySource { () =>
      val headers = cachedHeadersRef.get()
      if (headers.isEmpty) source
      else Source.single(headers.syncPrefix).concat(source)
    }.mapMaterializedValue(_ => NotUsed)

  private final class CacheStage(
    cachedHeadersRef: AtomicReference[CachedHeaders]
  , onHeadersUpdated: CachedHeaders => Unit
  ) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("MpegTsSync.in")
    val out: Outlet[ByteString] = Outlet("MpegTsSync.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var carry: ByteString = ByteString.empty
      private var detectedPmtPid: Option[Int] = None

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val incoming = grab(in)
          var combined = carry ++ incoming

          // Strip any leading non-sync bytes to ensure packet alignment
          var start = 0
          while (start < combined.length && combined(start) != 0x47.toByte) {
            start += 1
          }
          if (start > 0) {
            combined = combined.drop(start)
          }

          val fullLen = (combined.length / PacketSize) * PacketSize
          if (fullLen > 0) {
            val toPush = combined.take(fullLen)
            carry = combined.drop(fullLen)
            val arr = toPush.toArray
            var pos = 0
            var updated = cachedHeadersRef.get()

            while (pos + PacketSize <= fullLen) {
              if (arr(pos) == 0x47.toByte) {
                val pid = ((arr(pos + 1) & 0x1F) << 8) | (arr(pos + 2) & 0xFF)
                if (pid == PatPid) {
                  val patPacket = ByteString(java.util.Arrays.copyOfRange(arr, pos, pos + PacketSize))
                  extractPmtPid(arr, pos).foreach { pmtPid =>
                    detectedPmtPid = Some(pmtPid)
                  }
                  updated = updated.copy(pat = Some(patPacket))
                } else if (detectedPmtPid.contains(pid)) {
                  val pmtPacket = ByteString(java.util.Arrays.copyOfRange(arr, pos, pos + PacketSize))
                  updated = updated.copy(pmt = Some(pmtPacket))
                }
                pos += PacketSize
              } else {
                var found = -1
                var scan = pos + 1
                while (scan + PacketSize <= fullLen && found < 0) {
                  if (arr(scan) == 0x47.toByte) found = scan
                  else scan += 1
                }
                if (found < 0) pos = fullLen
                else pos = found
              }
            }

            val prev = cachedHeadersRef.get()
            if (updated != prev) {
              cachedHeadersRef.set(updated)
              onHeadersUpdated(updated)
            }
            push(out, toPush)
          } else {
            carry = combined
            pull(in)
          }
        }

        override def onUpstreamFinish(): Unit = {
          var start = 0
          while (start < carry.length && carry(start) != 0x47.toByte) {
            start += 1
          }
          val synced = if (start > 0) carry.drop(start) else carry
          val fullLen = (synced.length / PacketSize) * PacketSize
          if (fullLen > 0) {
            emit(out, synced.take(fullLen))
          }
          completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }
}
