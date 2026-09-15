package app.tuner

import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.zip.CRC32

import org.slf4j.LoggerFactory

import scala.util.Try

import app.config.AppConfig

object HDHomeRunDiscovery {
  val log = LoggerFactory.getLogger(this.getClass)

  val Port: Int = 65001
  val TypeDiscoverReq: Int = 0x0002
  val TypeDiscoverRpy: Int = 0x0003

  val TagDeviceType: Byte = 0x01
  val TagDeviceId: Byte = 0x02
  val TagTunerCount: Byte = 0x10
  val TagLineupUrl: Byte = 0x27
  val TagBaseUrl: Byte = 0x2A
  val TagDeviceAuthStr: Byte = 0x2B

  val DeviceTypeWildcard: Long = 0xFFFFFFFFL
  val DeviceTypeTuner: Long = 0x00000001L
  val DeviceIdWildcard: Long = 0xFFFFFFFFL

  final case class ParsedRequest(
    deviceType: Option[Long]
  , deviceId: Option[Long]
  )

  def parseRequest(bytes: Array[Byte], length: Int): Option[ParsedRequest] = {
    if (length < 8) None
    else {
      val bb = ByteBuffer.wrap(bytes, 0, length)
      val packetType = bb.getShort() & 0xFFFF
      if (packetType != TypeDiscoverReq) None
      else {
        val payloadLen = bb.getShort() & 0xFFFF
        if (4 + payloadLen + 4 > length) None
        else {
          val crc = new CRC32()
          crc.update(bytes, 0, 4 + payloadLen)
          val expectedCrc = crc.getValue() & 0xFFFFFFFFL
          val b0 = bytes(4 + payloadLen).toLong & 0xFFL
          val b1 = bytes(4 + payloadLen + 1).toLong & 0xFFL
          val b2 = bytes(4 + payloadLen + 2).toLong & 0xFFL
          val b3 = bytes(4 + payloadLen + 3).toLong & 0xFFL
          val leCrc = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0
          if (expectedCrc != leCrc) {
            log.debug("[discovery] CRC mismatch expected={} got={}", expectedCrc, leCrc)
            None
          } else {
            var devType: Option[Long] = None
            var devId: Option[Long] = None
            var pos = 4
            val end = 4 + payloadLen
            while (pos < end) {
              val tag = bytes(pos)
              pos += 1
              if (pos < end) {
                val lenByte = bytes(pos).toInt & 0xFF
                pos += 1
                val tagLen = if ((lenByte & 0x80) != 0 && pos < end) {
                  val next = bytes(pos).toInt & 0xFF
                  pos += 1
                  (lenByte & 0x7F) | (next << 7)
                } else lenByte

                if (pos + tagLen <= end) {
                  if (tag == TagDeviceType && tagLen == 4) {
                    val tbb = ByteBuffer.wrap(bytes, pos, 4)
                    devType = Some(tbb.getInt().toLong & 0xFFFFFFFFL)
                  } else if (tag == TagDeviceId && tagLen == 4) {
                    val ibb = ByteBuffer.wrap(bytes, pos, 4)
                    devId = Some(ibb.getInt().toLong & 0xFFFFFFFFL)
                  }
                  pos += tagLen
                } else {
                  pos = end
                }
              }
            }
            Some(ParsedRequest(devType, devId))
          }
        }
      }
    }
  }

  def buildReply(
    deviceIdHex: String
  , tunerCount: Int
  , baseUrl: String
  , lineupUrl: String
  , deviceAuth: String = "tabloauth123"
  ): Array[Byte] = {
    val devIdLong = Try(java.lang.Long.parseLong(deviceIdHex, 16)).getOrElse(0x12345678L)
    val baseBytes = (baseUrl + "\u0000").getBytes("UTF-8")
    val lineupBytes = (lineupUrl + "\u0000").getBytes("UTF-8")
    val authBytes = (deviceAuth + "\u0000").getBytes("UTF-8")

    val baos = new java.io.ByteArrayOutputStream()

    def writeTlv(tag: Byte, value: Array[Byte]): Unit = {
      baos.write(tag.toInt)
      val len = value.length
      if (len <= 127) {
        baos.write(len)
      } else {
        baos.write((len & 0x7F) | 0x80)
        baos.write((len >> 7) & 0xFF)
      }
      baos.write(value, 0, value.length)
    }

    val dtBuf = ByteBuffer.allocate(4)
    dtBuf.putInt(DeviceTypeTuner.toInt)
    writeTlv(TagDeviceType, dtBuf.array())

    val idBuf = ByteBuffer.allocate(4)
    idBuf.putInt(devIdLong.toInt)
    writeTlv(TagDeviceId, idBuf.array())

    writeTlv(TagTunerCount, Array(tunerCount.toByte))
    writeTlv(TagBaseUrl, baseBytes)
    writeTlv(TagLineupUrl, lineupBytes)
    writeTlv(TagDeviceAuthStr, authBytes)

    val payload = baos.toByteArray
    val payloadLen = payload.length

    val packetBuf = ByteBuffer.allocate(4 + payloadLen + 4)
    packetBuf.putShort(TypeDiscoverRpy.toShort)
    packetBuf.putShort(payloadLen.toShort)
    packetBuf.put(payload)

    val crc = new CRC32()
    crc.update(packetBuf.array(), 0, 4 + payloadLen)
    val crcVal = crc.getValue() & 0xFFFFFFFFL

    packetBuf.put((crcVal & 0xFF).toByte)
    packetBuf.put(((crcVal >> 8) & 0xFF).toByte)
    packetBuf.put(((crcVal >> 16) & 0xFF).toByte)
    packetBuf.put(((crcVal >> 24) & 0xFF).toByte)

    packetBuf.array()
  }

  def start(config: AppConfig): AutoCloseable =
    if (!config.proxy.enableUdpDiscovery) {
      log.info("[discovery] UDP discovery disabled by configuration")
      new AutoCloseable { override def close(): Unit = () }
    } else {
      Try[AutoCloseable] {
        val channel = DatagramChannel.open()
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, java.lang.Boolean.TRUE)
        channel.bind(new InetSocketAddress(Port))
        channel.configureBlocking(true)
        log.info("[discovery] listening on UDP port={}", Port)

        @volatile var running = true

        val thread = new Thread(new Runnable {
          override def run(): Unit = {
            val buf = ByteBuffer.allocate(1500)
            while (running && channel.isOpen) {
              try {
                buf.clear()
                val sender = channel.receive(buf)
                if (sender != null) {
                  val length = buf.position()
                  val bytes = buf.array()
                  parseRequest(bytes, length) match {
                    case Some(req) =>
                      val matchesType =
                        req.deviceType.contains(DeviceTypeWildcard) ||
                        req.deviceType.contains(DeviceTypeTuner) ||
                        req.deviceType.isEmpty
                      val reqId = req.deviceId.getOrElse(DeviceIdWildcard)
                      val ourIdLong = Try(java.lang.Long.parseLong(config.proxy.deviceId, 16)).getOrElse(0x12345678L)
                      val matchesId = reqId == DeviceIdWildcard || reqId == ourIdLong
                      if (matchesType && matchesId) {
                        log.debug("[discovery] discover request from={}, sending reply", sender)
                        val reply = buildReply(
                          deviceIdHex = config.proxy.deviceId
                        , tunerCount = config.proxy.tunerCount
                        , baseUrl = s"http://${config.proxy.bindHost}:${config.proxy.bindPort}"
                        , lineupUrl = s"http://${config.proxy.bindHost}:${config.proxy.bindPort}/lineup.json"
                        )
                        val replyBuf = ByteBuffer.wrap(reply)
                        val _ = channel.send(replyBuf, sender)
                      }
                    case None => ()
                  }
                }
              } catch {
                case _: java.nio.channels.AsynchronousCloseException => running = false
                case _: java.nio.channels.ClosedChannelException => running = false
                case ex: Throwable if running =>
                  log.debug("[discovery] receive error", ex)
              }
            }
          }
        }, "hdhomerun-discovery-thread")

        thread.setDaemon(true)
        thread.start()

        new AutoCloseable {
          override def close(): Unit = {
            running = false
            val _ = Try(channel.close())
          }
        }
      }.recover { case ex =>
        log.warn("[discovery] could not bind UDP port={}, auto-discovery disabled: {}", Port, ex.getMessage)
        new AutoCloseable { override def close(): Unit = () }
      }.get
    }
}
