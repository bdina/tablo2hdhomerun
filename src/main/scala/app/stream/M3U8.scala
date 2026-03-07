package app.stream

import java.net.URI

import scala.util.Try

object M3U8 {
  case class Segment(uri: String, duration: Double, title: Option[String], byteRange: Option[(Long, Long)] = None)
  case class Playlist(
    targetDuration: Int
  , mediaSequence: Int
  , segments: Seq[Segment]
  , isEndList: Boolean
  )

  def parse(raw: String): Playlist = {
    val lines = raw.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    var targetDuration = 10
    var mediaSequence = 0
    var isEndList = false
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
    while (i < lines.length) {
      val line = lines(i)
      if (line == "#EXTM3U") {
        i += 1
      } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
        targetDuration = Try(line.substring("#EXT-X-TARGETDURATION:".length).trim.toInt).toOption.getOrElse(10)
        i += 1
      } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
        mediaSequence = Try(line.substring("#EXT-X-MEDIA-SEQUENCE:".length).trim.toInt).toOption.getOrElse(0)
        i += 1
      } else if (line == "#EXT-X-ENDLIST") {
        isEndList = true
        i += 1
      } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
        applyByteRange(line.substring("#EXT-X-BYTERANGE:".length).trim)
        i += 1
      } else if (line.startsWith("#EXTINF:")) {
        val rest = line.substring("#EXTINF:".length).trim
        val (durationStr, titleOpt) = parseExtinf(rest)
        val duration = Try(durationStr.toDouble).toOption.getOrElse(0.0)
        i += 1
        if (i < lines.length && lines(i).startsWith("#EXT-X-BYTERANGE:")) {
          applyByteRange(lines(i).substring("#EXT-X-BYTERANGE:".length).trim)
          i += 1
        }
        if (i < lines.length && !lines(i).startsWith("#")) {
          val uri = lines(i).trim
          segmentBuilder += Segment(uri = uri, duration = duration, title = titleOpt, byteRange = pendingByteRange)
          pendingByteRange = None
          i += 1
        } else {
          i += 1
        }
      } else if (!line.startsWith("#") && line.nonEmpty) {
        segmentBuilder += Segment(uri = line, duration = 0.0, title = None, byteRange = pendingByteRange)
        pendingByteRange = None
        i += 1
      } else {
        i += 1
      }
    }
    Playlist(
      targetDuration = targetDuration, mediaSequence = mediaSequence
    , segments = segmentBuilder.result(), isEndList = isEndList
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
