package app

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Properties
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner
import scala.util.Using

@RunWith(classOf[JUnitRunner])
class BuildAutomationSpec extends AnyFlatSpec with Matchers {

  private val projectRoot: Path = Paths.get(System.getProperty("user.dir"))

  private def readIgnorePatterns(path: Path): Set[String] =
    Files
      .readString(path, StandardCharsets.UTF_8)
      .linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .toSet

  ".gitignore" should "ignore Gradle build outputs and untracked local artifacts for clean container builds" in {
    val gitignore = projectRoot.resolve(".gitignore")
    val _ = Files.exists(gitignore) shouldBe true
    val patterns = readIgnorePatterns(gitignore)

    val requiredPatterns = Set(
      "/.gradle/"
    , "/build/"
    , "/buildSrc/.gradle/"
    , "/buildSrc/build/"
    , ".tags"
    , "AGENTS.md"
    , "GEMINI.md"
    )
    requiredPatterns.foreach { pattern =>
      val _ = withClue(s"Missing required .gitignore pattern '$pattern': ") {
        patterns should contain(pattern)
      }
    }
  }

  ".dockerignore" should "exclude untracked build/local artifacts without hiding tracked docs from JGit" in {
    val dockerignore = projectRoot.resolve(".dockerignore")
    val _ = Files.exists(dockerignore) shouldBe true
    val patterns = readIgnorePatterns(dockerignore)

    val requiredExclusions = Set(
      "build/"
    , ".gradle/"
    , "buildSrc/build/"
    , "buildSrc/.gradle/"
    , ".tags"
    , "**/.tags"
    , "AGENTS.md"
    , "GEMINI.md"
    )
    requiredExclusions.foreach { pattern =>
      val _ = withClue(s"Missing required .dockerignore pattern '$pattern': ") {
        patterns should contain(pattern)
      }
    }

    val forbiddenTrackedExclusions = Set("docs/", "docs", "*.md", "**/*.md", "README.md", ".gitignore")
    forbiddenTrackedExclusions.foreach { pattern =>
      val _ = withClue(s".dockerignore must not exclude Git-tracked '$pattern': ") {
        patterns should not contain pattern
      }
    }
  }

  "build.gradle" should "reuse project.version in jar manifests and define verifyCleanVersion" in {
    val buildGradle = Files.readString(projectRoot.resolve("build.gradle"), StandardCharsets.UTF_8)
    val gitVersionCalls = "gitVersion\\(\\)".r.findAllIn(buildGradle).length

    val _ = gitVersionCalls shouldBe 1
    val _ = buildGradle should include("tasks.register('verifyCleanVersion')")
    val _ = buildGradle should include("'App-Version': project.version.toString()")
    val _ = buildGradle should include("'Implementation-Version': project.version.toString()")
    buildGradle should include("requireCleanVersion")
  }

  "Dockerfiles" should "wire REQUIRE_CLEAN_VERSION build arg to Gradle" in {
    List("Dockerfile.jvm", "Dockerfile.native").foreach { name =>
      val content = Files.readString(projectRoot.resolve(name), StandardCharsets.UTF_8)
      val _ = withClue(s"$name missing REQUIRE_CLEAN_VERSION arg: ") {
        content should include("ARG REQUIRE_CLEAN_VERSION=false")
      }
      val _ = withClue(s"$name not forwarding requireCleanVersion property to gradle: ") {
        content should include("-PrequireCleanVersion=${REQUIRE_CLEAN_VERSION}")
      }
    }
  }

  "version.properties" should "match Tablo2HDHomeRun.appVersion and resolve a valid git version" in {
    val streamOpt = Option(getClass.getResourceAsStream("/version.properties"))
    val _ = streamOpt shouldBe defined

    val resourceVersion = streamOpt.flatMap { in =>
      Using.resource(in) { s =>
        val props = new Properties()
        props.load(s)
        Option(props.getProperty("version")).map(_.trim).filter(_.nonEmpty)
      }
    }

    val _ = resourceVersion shouldBe Some(Tablo2HDHomeRun.appVersion)
    val _ = Tablo2HDHomeRun.appVersion should not be "dev"
    Tablo2HDHomeRun.appVersion should not be "unspecified"
  }
}
