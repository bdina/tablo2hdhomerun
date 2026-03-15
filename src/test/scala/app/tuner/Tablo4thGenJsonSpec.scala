package app.tuner

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import spray.json._

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
}
