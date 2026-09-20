package app.tuner

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import spray.json._

import org.apache.pekko.http.scaladsl.model.Uri

import app.tuner.Tablo4thGen.Auth.JsonProtocol._
import app.tuner.Tablo4thGen.Lineup.JsonProtocol._
import app.tuner.Tablo4thGen.Channel.Response.JsonProtocol._

@RunWith(classOf[JUnitRunner])
class Tablo4thGenJsonSpec extends AnyFlatSpec with Matchers {

  "LoginResponse" should "round-trip to JSON" in {
    val v = Tablo4thGen.Auth.LoginResponse(
      access_token = Some("token123"),
      token_type = Some("Bearer"),
      is_verified = Some(true),
      code = Some(0),
      message = None
    )
    val json = v.toJson
    json.convertTo[Tablo4thGen.Auth.LoginResponse] shouldBe v
  }

  "AccountInfo" should "round-trip with profiles and devices" in {
    val profile = Tablo4thGen.Auth.AccountProfile(identifier = "pid1", name = "Profile 1")
    val device = Tablo4thGen.Auth.AccountDevice(
      serverId = "sid1",
      name = "Device 1",
      `type` = Some("tablo"),
      url = Some("http://192.168.1.1"),
      reachability = Some("local")
    )
    val v = Tablo4thGen.Auth.AccountInfo(
      identifier = Some("acc1"),
      profiles = Some(Seq(profile)),
      devices = Some(Seq(device)),
      code = Some(0),
      message = None
    )
    val json = v.toJson
    val parsed = json.convertTo[Tablo4thGen.Auth.AccountInfo]
    val _ = parsed.identifier shouldBe v.identifier
    val _ = parsed.profiles.get.head.identifier shouldBe profile.identifier
    parsed.devices.get.head.serverId shouldBe device.serverId
  }

  "ChannelLineup" should "round-trip with OTA info" in {
    val ota = Tablo4thGen.Lineup.OtaChannelInfo(
      major = 5,
      minor = 1,
      callSign = Some("WXYZ"),
      network = Some("ABC"),
      streamUrl = Some("http://example.com/stream"),
      provider = None,
      canRecord = Some(true)
    )
    val v = Tablo4thGen.Lineup.ChannelLineup(
      identifier = "ch1",
      name = "Channel 1",
      kind = "ota",
      ota = Some(ota),
      ott = None
    )
    val json = v.toJson
    val parsed = json.convertTo[Tablo4thGen.Lineup.ChannelLineup]
    val _ = parsed.identifier shouldBe v.identifier
    val _ = parsed.ota.get.major shouldBe 5
    parsed.ota.get.callSign shouldBe Some("WXYZ")
  }

  "Watch4thGenResponse" should "round-trip to JSON" in {
    val v = Tablo4thGen.Channel.Response.Watch4thGenResponse(
      token = Some("watch-token"),
      expires = Some("2024-12-31T23:59:59Z"),
      keepalive = Some(30),
      playlist_url = Some("http://example.com/playlist.m3u8")
    )
    val json = v.toJson
    json.convertTo[Tablo4thGen.Channel.Response.Watch4thGenResponse] shouldBe v
  }

  "Watch4thGenRequest" should "round-trip to JSON with extra metadata" in {
    import app.tuner.Tablo4thGen.Channel.Request.Watch4thGenRequest.JsonProtocol._
    val deviceId = "531B3222-12BE-4C78-A45F-DEFBD2EF227F"
    val v = Tablo4thGen.Channel.Request.Watch4thGenRequest.forDevice(deviceId)
    val json = v.toJson
    val parsed = json.convertTo[Tablo4thGen.Channel.Request.Watch4thGenRequest]
    val _ = parsed.device_id shouldBe deviceId
    val _ = parsed.extra.deviceId shouldBe deviceId
    val _ = parsed.extra.deviceOS shouldBe "iOS"
    parsed.platform shouldBe "ios"
  }

  "ServerInfo" should "parse model with 4 tuners" in {
    val json = """{"model": {"name": "Tablo 4-Tuner", "tuners": 4}}""".parseJson
    val info = json.convertTo[Tablo4thGen.Channel.Response.ServerInfo]
    val _ = info.model.flatMap(_.tuners) shouldBe Some(4)
    info.detectedTuners shouldBe Some(4)
  }

  it should "parse model with 2 tuners" in {
    val json = """{"model": {"name": "Tablo 2-Tuner", "tuners": 2}}""".parseJson
    val info = json.convertTo[Tablo4thGen.Channel.Response.ServerInfo]
    info.detectedTuners shouldBe Some(2)
  }

  it should "support root-level tuners" in {
    val json = """{"tuners": 4}""".parseJson
    val info = json.convertTo[Tablo4thGen.Channel.Response.ServerInfo]
    info.detectedTuners shouldBe Some(4)
  }

  it should "return None when tuners not present" in {
    val json = """{"model": {"name": "Tablo Unknown"}}""".parseJson
    val info = json.convertTo[Tablo4thGen.Channel.Response.ServerInfo]
    info.detectedTuners shouldBe None
  }

  "Lineup.isHd" should "identify primary OTA .1 channels as HD" in {
    val _ = Tablo4thGen.Lineup.isHd("ota", 1, "WFXTDT1", Some("WFXTDT1")) shouldBe true
    val _ = Tablo4thGen.Lineup.isHd("ota", 1, "WBZDT1", Some("WBZDT1")) shouldBe true
    val _ = Tablo4thGen.Lineup.isHd("ota", 1, "WCVBDT1", Some("WCVBDT1")) shouldBe true
    Tablo4thGen.Lineup.isHd("ota", 1, "WBTSCD1", Some("WBTSCD1")) shouldBe true
  }

  it should "identify explicit HD markers on OTA subchannels as HD" in {
    val _ = Tablo4thGen.Lineup.isHd("ota", 2, "WUSA-HD", Some("WUSA-HD")) shouldBe true
    val _ = Tablo4thGen.Lineup.isHd("ota", 2, "Channel 2", Some("WGBH HD")) shouldBe true
    val _ = Tablo4thGen.Lineup.isHd("ota", 2, "Channel 2", Some("WCVB-DT")) shouldBe true
    Tablo4thGen.Lineup.isHd("ota", 2, "Channel 2", Some("WGBH-HDTV")) shouldBe true
  }

  it should "identify standard OTA subchannels without HD markers as SD" in {
    val _ = Tablo4thGen.Lineup.isHd("ota", 2, "Start TV", Some("WBZDT2")) shouldBe false
    val _ = Tablo4thGen.Lineup.isHd("ota", 2, "MeTV", Some("WCVBDT2")) shouldBe false
    val _ = Tablo4thGen.Lineup.isHd("ota", 2, "This TV", Some("WHDHDT2")) shouldBe false
    Tablo4thGen.Lineup.isHd("ota", 3, "Grit", Some("WFXTDT3")) shouldBe false
  }

  it should "identify OTT channels with HD in name or callsign as HD" in {
    val _ = Tablo4thGen.Lineup.isHd("ott", 0, "ION HD", None) shouldBe true
    val _ = Tablo4thGen.Lineup.isHd("ott", 0, "WeatherNation", Some("WN-HD")) shouldBe true
    Tablo4thGen.Lineup.isHd("ott", 0, "welcomehome", None) shouldBe false
  }

  it should "produce HD=1 in channelToJsValue for primary OTA channels" in {
    val ota = Tablo4thGen.Lineup.OtaChannelInfo(
      major = 25,
      minor = 1,
      callSign = Some("WFXTDT1"),
      network = Some("FOX"),
      streamUrl = None,
      provider = None,
      canRecord = Some(true)
    )
    val ch = Tablo4thGen.Lineup.ChannelLineup(
      identifier = "S20362_025_01",
      name = "WFXTDT1",
      kind = "ota",
      ota = Some(ota),
      ott = None
    )
    val js = Tablo4thGen.Lineup.channelToJsValue(ch, Uri("http://127.0.0.1:8080"))
    val obj = js.asJsObject.fields
    val hdVal = obj("HD")
    val guideNum = obj("GuideNumber")
    val chanType = obj("type")
    val _ = hdVal shouldBe spray.json.JsNumber(1)
    val _ = guideNum shouldBe spray.json.JsString("25.1")
    chanType shouldBe spray.json.JsString("antenna")
  }
}

