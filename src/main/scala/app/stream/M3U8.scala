package app.stream

import java.net.URI

import org.parboiled2._

import scala.util.{ Failure, Success, Try }

object M3U8 {
  case class Segment(uri: String, duration: Double, title: Option[String], byteRange: Option[(Long, Long)] = None)
  case class Playlist(
    targetDuration: Int
  , mediaSequence: Int
  , segments: Seq[Segment]
  , isEndList: Boolean
  , version: Option[Int] = None
  )

  def parse(raw: String): Playlist = {
    val lines = raw.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    val events = lines.map(M3U8LineParser.parseLine)
    foldEvents(events)
  }

  private def foldEvents(events: Vector[M3U8LineParser.LineEvent]): Playlist = {
    var targetDuration = 10
    var mediaSequence = 0
    var isEndList = false
    var version: Option[Int] = None
    var pendingByteRange: Option[(Long, Long)] = None
    var nextImplicitOffset: Long = 0
    val segmentBuilder = Seq.newBuilder[Segment]

    def applyByteRange(value: String): Unit = {
      val atIdx = value.indexOf('@')
      if (atIdx >= 0) {
        val length = Try(value.substring(0, atIdx).toLong).toOption.getOrElse(0L)
        val offset = Try(value.substring(atIdx + 1).toLong).toOption.getOrElse(0L)
        pendingByteRange = Some((offset, length))
        nextImplicitOffset = offset + length
      } else {
        val length = Try(value.toLong).toOption.getOrElse(0L)
        pendingByteRange = Some((nextImplicitOffset, length))
        nextImplicitOffset += length
      }
    }

    var i = 0
    while (i < events.length) {
      events(i) match {
        case M3U8LineParser.LineEvent.Extm3u =>
          i += 1
        case M3U8LineParser.LineEvent.Version(v) =>
          version = Try(v.toInt).toOption
          i += 1
        case M3U8LineParser.LineEvent.TargetDuration(v) =>
          targetDuration = Try(v.toInt).toOption.getOrElse(10)
          i += 1
        case M3U8LineParser.LineEvent.MediaSequence(v) =>
          mediaSequence = Try(v.toInt).toOption.getOrElse(0)
          i += 1
        case M3U8LineParser.LineEvent.EndList =>
          isEndList = true
          i += 1
        case M3U8LineParser.LineEvent.ByteRange(v) =>
          applyByteRange(v)
          i += 1
        case M3U8LineParser.LineEvent.Extinf(rest) =>
          val (durationStr, titleOpt) = parseExtinf(rest)
          val duration = Try(durationStr.toDouble).toOption.getOrElse(0.0)
          i += 1
          if (i < events.length) {
            events(i) match {
              case M3U8LineParser.LineEvent.ByteRange(v) =>
                applyByteRange(v)
                i += 1
              case _ =>
            }
          }
          if (i < events.length) {
            events(i) match {
              case M3U8LineParser.LineEvent.Uri(uri) =>
                segmentBuilder += Segment(uri = uri, duration = duration, title = titleOpt, byteRange = pendingByteRange)
                pendingByteRange = None
                i += 1
              case _ =>
                i += 1
            }
          } else {
            i += 1
          }
        case M3U8LineParser.LineEvent.Uri(uri) =>
          segmentBuilder += Segment(uri = uri, duration = 0.0, title = None, byteRange = pendingByteRange)
          pendingByteRange = None
          i += 1
        case M3U8LineParser.LineEvent.Unknown =>
          i += 1
      }
    }
    Playlist(
      targetDuration = targetDuration
    , mediaSequence = mediaSequence
    , segments = segmentBuilder.result()
    , isEndList = isEndList
    , version = version
    )
  }

  private def parseExtinf(rest: String): (String, Option[String]) = {
    val comma = rest.indexOf(',')
    if (comma < 0) (rest, None)
    else (rest.substring(0, comma).trim, Some(rest.substring(comma + 1).trim).filter(_.nonEmpty))
  }

  def resolveSegmentUri(segmentUri: String, basePlaylistUrl: String): String = {
    Try {
      val base = URI.create(basePlaylistUrl)
      val resolved = base.resolve(segmentUri)
      resolved.toString
    }.getOrElse(segmentUri)
  }
}

private object M3U8LineParser {
  sealed trait LineEvent
  object LineEvent {
    case object Extm3u extends LineEvent
    case object EndList extends LineEvent
    case class Version(value: String) extends LineEvent
    case class TargetDuration(value: String) extends LineEvent
    case class MediaSequence(value: String) extends LineEvent
    case class ByteRange(value: String) extends LineEvent
    case class Extinf(rest: String) extends LineEvent
    case class Uri(value: String) extends LineEvent
    case object Unknown extends LineEvent
  }

  def parseLine(line: String): LineEvent =
    new LineParser(line).line.run() match {
      case Success(event) => event
      case Failure(_) => LineEvent.Unknown
    }

  private final class LineParser(val input: ParserInput) extends Parser {
    import LineEvent.*

    def line: Rule1[LineEvent] = rule {
      extm3uLine | endListLine | versionLine | targetDurationLine | mediaSequenceLine | byteRangeLine | extinfLine | unknownTagLine | uriLine
    }

    def eol: Rule0 = rule { EOI }

    def rest: Rule1[String] = rule { capture(zeroOrMore(noneOf("\n\r"))) ~> (_.trim) }

    def extm3uLine: Rule1[LineEvent] = rule { "#EXTM3U" ~ eol ~> (() => Extm3u: LineEvent) }

    def endListLine: Rule1[LineEvent] = rule { "#EXT-X-ENDLIST" ~ eol ~> (() => EndList: LineEvent) }

    def versionLine: Rule1[LineEvent] = rule { "#EXT-X-VERSION:" ~ rest ~ eol ~> (v => Version(v)) }

    def targetDurationLine: Rule1[LineEvent] = rule {
      "#EXT-X-TARGETDURATION:" ~ rest ~ eol ~> (v => TargetDuration(v))
    }

    def mediaSequenceLine: Rule1[LineEvent] = rule {
      "#EXT-X-MEDIA-SEQUENCE:" ~ rest ~ eol ~> (v => MediaSequence(v))
    }

    def byteRangeLine: Rule1[LineEvent] = rule { "#EXT-X-BYTERANGE:" ~ rest ~ eol ~> (v => ByteRange(v)) }

    def extinfLine: Rule1[LineEvent] = rule { "#EXTINF:" ~ rest ~ eol ~> (v => Extinf(v)) }

    def unknownTagLine: Rule1[LineEvent] = rule { "#" ~ zeroOrMore(noneOf("\n\r")) ~ eol ~> (() => Unknown: LineEvent) }

    def uriLine: Rule1[LineEvent] = rule { rest ~ eol ~> (v => Uri(v)) }
  }
}