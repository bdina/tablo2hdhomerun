package app.sys

import java.io.File
import java.nio.file.{Files, Paths}

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import scala.util.{Failure, Success}
import spray.json._
import DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class SafeJsonSpec extends AnyFlatSpec with Matchers {

  private case class SamplePayload(id: Int, name: String)
  private given sampleFormat: RootJsonFormat[SamplePayload] = jsonFormat2(SamplePayload.apply)

  "SafeJson.parseAs" should "decode valid JSON and reject invalid payloads as Failure without throwing" in {
    val _ = SafeJson.parseAs[SamplePayload](""" {"id": 42, "name": "tablo"} """) shouldBe Success(SamplePayload(42, "tablo"))

    val _ = SafeJson.parseAs[SamplePayload](null).isFailure shouldBe true
    val _ = SafeJson.parseAs[SamplePayload]("   ").isFailure shouldBe true
    val _ = SafeJson.parseAs[SamplePayload]("{not valid json").isFailure shouldBe true
    SafeJson.parseAs[SamplePayload]("""{"id":"wrong-type","name":123}""").isFailure shouldBe true
  }

  "FFMpegDelegate.prepare" should "return TranscodeError values with NoStackTrace instead of throwing" in {
    val _ = FFMpegDelegate.prepare(new File("relative-no-parent.ts")) should matchPattern {
      case Failure(_: TranscodeError.InvalidPath) =>
    }
    val _ = TranscodeError.InvalidPath(new File("x.ts")).getStackTrace shouldBe empty

    val dir = Files.createTempDirectory("ffmpeg-prepare-spec-")
    try {
      val src = dir.resolve("recording.ts")
      val _ = Files.writeString(src, "ts")
      val recent = src.toFile
      val _ = FFMpegDelegate.prepare(recent) should matchPattern {
        case Failure(_: TranscodeError.IncompleteFile) =>
      }

      val _ = recent.setLastModified(System.currentTimeMillis() - 60000L)
      val _ = FFMpegDelegate.prepare(recent) shouldBe Success(src)

      val dup = dir.resolve("recording.mp4")
      val _ = Files.writeString(dup, "mp4")
      val _ = FFMpegDelegate.prepare(recent) should matchPattern {
        case Failure(_: TranscodeError.DuplicateFile) =>
      }
      val _: FFMpegDelegate.Error = TranscodeError.DuplicateFile(recent)
      FFMpegDelegate.Error.IncompleteFile(recent) shouldBe a[TranscodeError]
    } finally {
      val _ = Files.deleteIfExists(dir.resolve("recording.mp4"))
      val _ = Files.deleteIfExists(dir.resolve("recording.ts"))
      val _ = Files.deleteIfExists(dir)
    }
  }

  "FsUtil.Attr.write" should "return Failure instead of throwing when extended attributes cannot be written" in {
    val missing = Paths.get("/nonexistent-dir-tablo-spec/file.ts")
    FsUtil.Attr.write(missing, FsUtil.Attr.Key.FS_STATE, FsUtil.Attr.Value.LOCK) match {
      case Failure(_: TranscodeError.MissingXattr) => succeed
      case Failure(_) => succeed
      case Success(_) => fail("expected Failure on missing path")
    }
  }
}
