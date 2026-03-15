package app.tuner

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import app.tuner.TabloLegacy.Guide.{ChannelGuide, Program, XMLTVFormatter}

@RunWith(classOf[JUnitRunner])
class XMLTVFormatterSpec extends AnyFlatSpec with Matchers {

  "XMLTVFormatter.formatTimestamp" should "parse ISO instant" in {
    val s = "2024-01-15T18:00:00Z"
    val out = XMLTVFormatter.formatTimestamp(s)
    val _ = out should include("2024-01-15")
    out should include("18:00:00")
  }

  it should "parse yyyy-MM-dd HH:mm:ss" in {
    val s = "2024-01-15 20:30:00"
    val out = XMLTVFormatter.formatTimestamp(s)
    out should not be empty
  }

  it should "fall back for unparseable string" in {
    val s = "not-a-date"
    val out = XMLTVFormatter.formatTimestamp(s)
    out should not be empty
  }

  "XMLTVFormatter.formatChannel" should "produce channel with id and display-names" in {
    val guide = ChannelGuide(channel_id = 1, call_sign = "WXYZ", major = 5, minor = 1, programs = Seq.empty)
    val elem = XMLTVFormatter.formatChannel(guide)
    val _ = (elem \ "@id").text shouldBe "5.1"
    val names = (elem \ "display-name").map(_.text)
    val _ = names should contain("WXYZ")
    val _ = names should contain("5.1")
    names should contain("WXYZ (5.1)")
  }

  "XMLTVFormatter.formatProgram" should "produce programme with start, stop, channel, title" in {
    val program = Program(
      id = "p1",
      title = "News Hour",
      description = Some("Evening news"),
      start_time = "2024-01-15T19:00:00Z",
      end_time = "2024-01-15T20:00:00Z",
      channel_id = 1,
      episode_title = Some("Episode 1"),
      season_number = None,
      episode_number = None,
      year = Some(2024),
      genre = Some("News"),
      rating = Some("TV-PG"),
      is_movie = false,
      is_sports = false,
      is_news = true
    )
    val channelGuide = ChannelGuide(channel_id = 1, call_sign = "WXYZ", major = 5, minor = 1, programs = Seq(program))
    val elem = XMLTVFormatter.formatProgram(program, channelGuide)
    val _ = (elem \ "title").text shouldBe "News Hour"
    val _ = (elem \ "sub-title").text shouldBe "Episode 1"
    val _ = (elem \ "desc").text shouldBe "Evening news"
    val _ = (elem \ "category").map(_.text) should contain("News")
    (elem \ "@channel").text shouldBe "5.1"
  }

  it should "include Movie category when is_movie" in {
    val program = Program(
      id = "p2",
      title = "A Movie",
      description = None,
      start_time = "2024-01-15T21:00:00Z",
      end_time = "2024-01-15T23:00:00Z",
      channel_id = 1,
      episode_title = None,
      season_number = None,
      episode_number = None,
      year = Some(2023),
      genre = None,
      rating = None,
      is_movie = true,
      is_sports = false,
      is_news = false
    )
    val channelGuide = ChannelGuide(channel_id = 1, call_sign = "ABC", major = 7, minor = 2, programs = Seq(program))
    val elem = XMLTVFormatter.formatProgram(program, channelGuide)
    (elem \ "category").map(_.text) should contain("Movie")
  }

  "XMLTVFormatter.formatGuide" should "produce tv root with channels and programmes" in {
    val program = Program(
      id = "p1",
      title = "Show",
      description = None,
      start_time = "2024-01-15T12:00:00Z",
      end_time = "2024-01-15T13:00:00Z",
      channel_id = 1,
      episode_title = None,
      season_number = None,
      episode_number = None,
      year = None,
      genre = None,
      rating = None,
      is_movie = false,
      is_sports = false,
      is_news = false
    )
    val guide = Seq(
      ChannelGuide(channel_id = 1, call_sign = "WXYZ", major = 5, minor = 1, programs = Seq(program))
    )
    val elem = XMLTVFormatter.formatGuide(guide)
    val _ = elem.label shouldBe "tv"
    val _ = (elem \ "channel").size shouldBe 1
    val _ = (elem \ "programme").size shouldBe 1
    val _ = (elem \ "channel" \ "display-name").head.text shouldBe "WXYZ"
    (elem \ "programme" \ "title").text shouldBe "Show"
  }
}
