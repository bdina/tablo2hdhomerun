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

  "gradle.properties" should "enable Gradle caching, parallel execution, and tuned JVM flags" in {
    val propsPath = projectRoot.resolve("gradle.properties")
    val _ = Files.exists(propsPath) shouldBe true
    val props = new Properties()
    Using.resource(Files.newBufferedReader(propsPath, StandardCharsets.UTF_8)) { reader =>
      props.load(reader)
    }

    val _ = props.getProperty("org.gradle.caching") shouldBe "true"
    val _ = props.getProperty("org.gradle.parallel") shouldBe "true"
    val jvmArgs = Option(props.getProperty("org.gradle.jvmargs")).getOrElse("")
    val _ = jvmArgs should include("-XX:+UseParallelGC")
    jvmArgs should include("-XX:+UseCompactObjectHeaders")
  }

  "build.gradle" should "resolve gitVersion once, optimize compile/test/jar tasks, and define verifyCleanVersion" in {
    val buildGradle = Files.readString(projectRoot.resolve("build.gradle"), StandardCharsets.UTF_8)
    val gitVersionCalls = "gitVersion\\(\\)".r.findAllIn(buildGradle).length

    val _ = gitVersionCalls shouldBe 1
    val _ = buildGradle should not include "mvnrepository.com"
    val _ = buildGradle should include("tasks.register('verifyCleanVersion')")
    val _ = buildGradle should include("tasks.register('warmDependencyCache')")
    val _ = buildGradle should include("'App-Version': resolvedProjectVersion")
    val _ = buildGradle should include("'Implementation-Version': resolvedProjectVersion")
    val _ = buildGradle should include("requireCleanVersion")
    val _ = buildGradle should include("-Ybackend-parallelism:")
    val _ = buildGradle should include("maxParallelForks")
    val _ = buildGradle should include("testReportsEnabled")
    buildGradle should include("singleShotCompile")
  }

  "NativeImageTask" should "declare incremental InputFile and OutputFile properties" in {
    val nativeTask = Files.readString(
      projectRoot.resolve("buildSrc/src/main/groovy/compiler/NativeImage.groovy")
    , StandardCharsets.UTF_8
    )
    val _ = nativeTask should include("@InputFile")
    val _ = nativeTask should include("getInputJar()")
    val _ = nativeTask should include("@OutputFile")
    nativeTask should include("getOutputBinary()")
  }

  "Dockerfiles" should "warm Gradle caches in a dedicated layer and run offline single-shot builds" in {
    List("Dockerfile.jvm", "Dockerfile.native").foreach { name =>
      val content = Files.readString(projectRoot.resolve(name), StandardCharsets.UTF_8)
      val _ = withClue(s"$name missing warmDependencyCache layer: ") {
        content should include("-PskipGitVersion=true warmDependencyCache")
      }
      val _ = withClue(s"$name missing offline/no-build-cache/single-shot build flags: ") {
        content should include("--offline --no-build-cache --no-watch-fs -PsingleShotCompile=true")
      }
      val _ = withClue(s"$name missing REQUIRE_CLEAN_VERSION arg: ") {
        content should include("ARG REQUIRE_CLEAN_VERSION=false")
      }
      val _ = withClue(s"$name not forwarding requireCleanVersion property to gradle: ") {
        content should include("-PrequireCleanVersion=${REQUIRE_CLEAN_VERSION}")
      }
    }
  }

  "docker-build-test.sh" should "persist Gradle caches in a Docker volume and default to incremental tests on latest" in {
    val script = Files.readString(projectRoot.resolve("docker-build-test.sh"), StandardCharsets.UTF_8)
    val _ = script should include("GRADLE_CACHE_VOLUME")
    val _ = script should include(":/root/.gradle")
    val _ = script should include("TAG=${TAG:-latest}")
    script should include("--no-watch-fs")
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
