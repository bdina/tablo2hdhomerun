package app.tuner

import java.net.InetAddress

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import org.apache.pekko.http.scaladsl.model.Uri

import spray.json._

import app.tuner.TabloLegacy.Response.Discover
import app.tuner.TabloLegacy.Response.Discover.JsonProtocol.discoverFormat

@RunWith(classOf[JUnitRunner])
class TabloLegacyDiscoverSpec extends AnyFlatSpec with Matchers {

  "Discover" should "round-trip to JSON and back" in {
    val localIp = InetAddress.getByName("127.0.0.1")
    val baseUrl = Uri("http://127.0.0.1:8080")
    val lineupUrl = baseUrl.withPath(Uri.Path("/lineup.json"))
    val discover = Discover(
      FriendlyName = "Test Proxy",
      LocalIP = localIp,
      BaseURL = baseUrl,
      LineupURL = lineupUrl,
      Manufacturer = "tablo2hdhomerun",
      ModelNumber = "HDHR3-US",
      FirmwareName = "hdhomerun3_atsc",
      FirmwareVersion = "20240101",
      DeviceID = "12345678",
      DeviceAuth = "tabloauth123"
    )
    val json = discover.toJson
    val parsed = json.convertTo[Discover]
    val _ = parsed.FriendlyName shouldBe discover.FriendlyName
    val _ = parsed.LocalIP.getHostAddress shouldBe discover.LocalIP.getHostAddress
    val _ = parsed.BaseURL.toString shouldBe discover.BaseURL.toString
    val _ = parsed.LineupURL.toString shouldBe discover.LineupURL.toString
    parsed.DeviceID shouldBe discover.DeviceID
  }

  it should "proxyAddress set host and port" in {
    val localIp = InetAddress.getByName("127.0.0.1")
    val baseUrl = Uri("http://192.168.1.1:8080")
    val discover = Discover(
      FriendlyName = "Test",
      LocalIP = localIp,
      BaseURL = baseUrl,
      LineupURL = baseUrl.withPath(Uri.Path("/lineup.json")),
      Manufacturer = "tablo2hdhomerun",
      ModelNumber = "HDHR3-US",
      FirmwareName = "hdhomerun3_atsc",
      FirmwareVersion = "20240101",
      DeviceID = "12345678",
      DeviceAuth = "tabloauth123"
    )
    val other = InetAddress.getByName("10.0.0.2")
    val proxied = discover.proxyAddress(other, 9090)
    val _ = proxied.authority.host.address().shouldBe("10.0.0.2")
    proxied.toString.should(include("9090"))
  }
}
