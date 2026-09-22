package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicReference

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

  final case class CachedHeaders(
    pat: Option[ByteString] = None
  , pmt: Option[ByteString] = None
  ) {
    def isEmpty: Boolean = pat.isEmpty && pmt.isEmpty

    def syncPrefix: ByteString = {
      val b = ByteString.newBuilder
      pat.foreach(b ++= _)
      pmt.foreach(b ++= _)
      b ++= MPEGTS_DISCONTINUITY_PACKET
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

  def cacheFlow(cachedHeadersRef: AtomicReference[CachedHeaders]): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new CacheStage(cachedHeadersRef))

  def primeClientSource(
    source: Source[ByteString, NotUsed]
  , cachedHeadersRef: AtomicReference[CachedHeaders]
  ): Source[ByteString, NotUsed] =
    Source.lazySource { () =>
      val headers = cachedHeadersRef.get()
      if (headers.isEmpty) source
      else Source.single(headers.syncPrefix).concat(source)
    }.mapMaterializedValue(_ => NotUsed)

  private final class CacheStage(cachedHeadersRef: AtomicReference[CachedHeaders])
      extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("MpegTsSync.in")
    val out: Outlet[ByteString] = Outlet("MpegTsSync.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var carry: ByteString = ByteString.empty
      private var detectedPmtPid: Option[Int] = None

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val incoming = grab(in)
          val combined = carry ++ incoming
          val arr = combined.toArray
          val fullLen = (arr.length / PacketSize) * PacketSize
          var pos = 0
          var updated = cachedHeadersRef.get()

          while (pos < fullLen) {
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
              while (scan < fullLen && found < 0) {
                if (arr(scan) == 0x47.toByte) found = scan
                else scan += 1
              }
              if (found < 0) pos = fullLen
              else pos = found
            }
          }

          cachedHeadersRef.set(updated)
          carry = combined.drop(fullLen)
          push(out, incoming)
        }

        override def onUpstreamFinish(): Unit = {
          if (carry.nonEmpty) {
            emit(out, carry)
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
