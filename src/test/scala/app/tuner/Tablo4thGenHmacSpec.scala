package app.tuner

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import app.tuner.Tablo4thGen.Hmac

@RunWith(classOf[JUnitRunner])
class Tablo4thGenHmacSpec extends AnyFlatSpec with Matchers {

  "Hmac.md5Hex" should "match standard MD5 for known input" in {
    Hmac.md5Hex("hello") shouldBe "5d41402abc4b2a76b9719d911017c592"
  }

  it should "return 32 hex characters for any input" in {
    val out = Hmac.md5Hex("any string")
    val _ = out should have length 32
    out.filter(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) shouldBe out
  }

  "Hmac.hmacMd5Hex" should "match RFC 2202 test case 1" in {
    val key = "\u000b" * 16
    val data = "Hi There"
    Hmac.hmacMd5Hex(data, key) shouldBe "9294727a3638bb1c13f48ef8158bfc9d"
  }

  it should "return 32 hex characters" in {
    val out = Hmac.hmacMd5Hex("data", "key")
    val _ = out should have length 32
    out.filter(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) shouldBe out
  }

  "Hmac.sign" should "return auth header starting with tablo and deviceKey" in {
    val (authHeader, date) = Hmac.sign("GET", "/path", None, "hashKey", "deviceKey")
    val _ = authHeader should startWith("tablo:deviceKey:")
    date should not be empty
  }

  it should "include body MD5 in signature when body is present" in {
    val (auth1, _) = Hmac.sign("POST", "/api", Some(""), "hk", "dk")
    val (auth2, _) = Hmac.sign("POST", "/api", Some("body"), "hk", "dk")
    auth1 should not equal auth2
  }

  it should "differ for different methods" in {
    val (authGet, _) = Hmac.sign("GET", "/p", None, "hk", "dk")
    val (authPost, _) = Hmac.sign("POST", "/p", None, "hk", "dk")
    authGet should not equal authPost
  }
}
