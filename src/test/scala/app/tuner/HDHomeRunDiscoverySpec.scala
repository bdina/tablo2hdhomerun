package app.tuner

import java.nio.ByteBuffer
import java.util.zip.CRC32

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HDHomeRunDiscoverySpec extends AnyFlatSpec with Matchers {

  def buildTestDiscoverRequest(deviceType: Long = HDHomeRunDiscovery.DeviceTypeTuner, deviceId: Long = HDHomeRunDiscovery.DeviceIdWildcard): Array[Byte] = {
    val baos = new java.io.ByteArrayOutputStream()

    // Tag 0x01: DeviceType (4 bytes)
    baos.write(HDHomeRunDiscovery.TagDeviceType.toInt)
    baos.write(4)
    val dtBuf = ByteBuffer.allocate(4)
    dtBuf.putInt(deviceType.toInt)
    baos.write(dtBuf.array())

    // Tag 0x02: DeviceId (4 bytes)
    baos.write(HDHomeRunDiscovery.TagDeviceId.toInt)
    baos.write(4)
    val idBuf = ByteBuffer.allocate(4)
    idBuf.putInt(deviceId.toInt)
    baos.write(idBuf.array())

    val payload = baos.toByteArray
    val payloadLen = payload.length

    val buf = ByteBuffer.allocate(4 + payloadLen + 4)
    buf.putShort(HDHomeRunDiscovery.TypeDiscoverReq.toShort)
    buf.putShort(payloadLen.toShort)
    buf.put(payload)

    val crc = new CRC32()
    crc.update(buf.array(), 0, 4 + payloadLen)
    val crcVal = crc.getValue() & 0xFFFFFFFFL

    buf.put((crcVal & 0xFF).toByte)
    buf.put(((crcVal >> 8) & 0xFF).toByte)
    buf.put(((crcVal >> 16) & 0xFF).toByte)
    buf.put(((crcVal >> 24) & 0xFF).toByte)

    buf.array()
  }

  "HDHomeRunDiscovery.parseRequest" should "successfully parse a valid discover request" in {
    val pkt = buildTestDiscoverRequest()
    val parsed = HDHomeRunDiscovery.parseRequest(pkt, pkt.length)
    val _ = parsed shouldBe defined
    val req = parsed.get
    val _ = req.deviceType shouldBe Some(HDHomeRunDiscovery.DeviceTypeTuner)
    req.deviceId shouldBe Some(HDHomeRunDiscovery.DeviceIdWildcard)
  }

  it should "reject a packet with invalid CRC" in {
    val pkt = buildTestDiscoverRequest()
    pkt(pkt.length - 1) = (pkt(pkt.length - 1) ^ 0xFF).toByte
    val parsed = HDHomeRunDiscovery.parseRequest(pkt, pkt.length)
    parsed shouldBe None
  }

  it should "reject a packet with unknown type" in {
    val pkt = buildTestDiscoverRequest()
    pkt(0) = 0x00
    pkt(1) = 0x99.toByte
    val parsed = HDHomeRunDiscovery.parseRequest(pkt, pkt.length)
    parsed shouldBe None
  }

  it should "reject truncated packets" in {
    val pkt = Array[Byte](0x00, 0x02, 0x00, 0x04)
    val parsed = HDHomeRunDiscovery.parseRequest(pkt, pkt.length)
    parsed shouldBe None
  }

  "HDHomeRunDiscovery.buildReply" should "produce a valid HDHomeRun discovery reply with correct CRC" in {
    val reply = HDHomeRunDiscovery.buildReply(
      deviceIdHex = "12345678"
    , tunerCount = 4
    , baseUrl = "http://192.168.2.24:8080"
    , lineupUrl = "http://192.168.2.24:8080/lineup.json"
    )

    val _ = reply.length should be > 12

    val bb = ByteBuffer.wrap(reply)
    val packetType = bb.getShort() & 0xFFFF
    val _ = packetType shouldBe HDHomeRunDiscovery.TypeDiscoverRpy

    val payloadLen = bb.getShort() & 0xFFFF
    val _ = (4 + payloadLen + 4) shouldBe reply.length

    // Verify CRC32
    val crc = new CRC32()
    crc.update(reply, 0, 4 + payloadLen)
    val expectedCrc = crc.getValue() & 0xFFFFFFFFL

    val b0 = reply(4 + payloadLen).toLong & 0xFFL
    val b1 = reply(4 + payloadLen + 1).toLong & 0xFFL
    val b2 = reply(4 + payloadLen + 2).toLong & 0xFFL
    val b3 = reply(4 + payloadLen + 3).toLong & 0xFFL
    val leCrc = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0

    leCrc shouldBe expectedCrc
  }
}
