package app.sys

import java.nio.file.Files

import org.junit.runner.RunWith
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatestplus.junit.JUnitRunner

import app.sys.FsScan._

@RunWith(classOf[JUnitRunner])
class FsScanSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike {

  "FsScan" should "return paths of matching files without fs.state xattr" in {
    val root = Files.createTempDirectory("fsscan-")
    try {
      val matchingFile = root.resolve("segment.ts")
      Files.createFile(matchingFile)
      val suffixOnlyFile = root.resolve("hosts")
      Files.createFile(suffixOnlyFile)
      val otherFile = root.resolve("readme.txt")
      Files.createFile(otherFile)
      val scanActor = testKit.spawn(FsScan(Seq("ts", "mkv")))
      val probe = testKit.createTestProbe[Ack]()
      scanActor ! Scan(root, probe.ref)
      val ack = probe.receiveMessage()
      val names = ack.paths.map(_.getFileName.toString)
      val _ = names should contain("segment.ts")
      val _ = names should not contain "hosts"
      names should not contain "readme.txt"
    } finally {
      Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
    }
  }

  "FsMonitor" should "reply with Ack when receiving Watch" in {
    val root = Files.createTempDirectory("fsmon-")
    try {
      app.AppContext.initialize(testKit.system.asInstanceOf[org.apache.pekko.actor.typed.ActorSystem[org.apache.pekko.NotUsed]])
      val monitor = testKit.spawn(FsMonitor())
      val probe = testKit.createTestProbe[FsMonitor.Ack]()
      monitor ! FsMonitor.Watch(root, Seq("ts", "mkv"), probe.ref)
      val ack = probe.receiveMessage()
      val _ = ack.path shouldBe root
      testKit.stop(monitor)
    } finally {
      Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
    }
  }

  "FsNotify.FsQueue.QueueProxy" should "complete and stop cleanly when transcode start skips a file" in {
    import scala.concurrent.duration.*
    val root = Files.createTempDirectory("fsproxy-")
    try {
      app.AppContext.initialize(testKit.system.asInstanceOf[org.apache.pekko.actor.typed.ActorSystem[org.apache.pekko.NotUsed]])
      val tsFile = root.resolve("fresh.ts")
      val mp4File = root.resolve("fresh.mp4")
      val _ = Files.createFile(tsFile)
      val _ = Files.createFile(mp4File)
      val proxy = testKit.spawn(FsNotify.FsQueue.QueueProxy(tsFile.toFile))
      val probe = testKit.createTestProbe[FsNotify.FsQueue.QueueProxy.Response]()
      proxy ! FsNotify.FsQueue.QueueProxy.Request.Notify(probe.ref)
      val _ = probe.expectMessage(3.seconds, FsNotify.FsQueue.QueueProxy.Response.Complete)
      probe.expectTerminated(proxy, 3.seconds)
    } finally {
      Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))
    }
  }
}
