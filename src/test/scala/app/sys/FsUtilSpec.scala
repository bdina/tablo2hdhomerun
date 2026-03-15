package app.sys

import java.nio.file.Files

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import app.sys.FsUtil.Attr

@RunWith(classOf[JUnitRunner])
class FsUtilSpec extends AnyFlatSpec with Matchers {

  "Attr.write" should "write a user-defined attribute to a path" in {
    val path = Files.createTempFile("fsutil-", ".tmp")
    try {
      val result = Attr.write(path, Attr.Key.FS_STATE, Attr.Value.LOCK)
      result.isSuccess shouldBe true
    } finally { val _ = Files.deleteIfExists(path) }
  }

  "Attr.lock" should "write fs.state=lock without throwing" in {
    val path = Files.createTempFile("fsutil-lock-", ".tmp")
    try {
      Attr.lock(path).isSuccess shouldBe true
    } finally { val _ = Files.deleteIfExists(path) }
  }

  "Attr.encoded" should "write fs.state=encoded without throwing" in {
    val path = Files.createTempFile("fsutil-encoded-", ".tmp")
    try {
      Attr.encoded(path).isSuccess shouldBe true
    } finally { val _ = Files.deleteIfExists(path) }
  }
}
