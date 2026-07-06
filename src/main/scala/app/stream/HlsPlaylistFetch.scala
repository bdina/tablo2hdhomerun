package app.stream

import org.apache.pekko.http.scaladsl.model.HttpHeader
import org.apache.pekko.http.scaladsl.model.headers.{EntityTag, `If-None-Match`, RawHeader}

object HlsPlaylistFetch {
  final case class Validators(
    etag: Option[String] = None
  , lastModified: Option[String] = None
  )

  def fromPollState(state: HlsPlaylistPoller.PollState): Validators =
    Validators(etag = state.etag, lastModified = state.lastModified)

  def applyToPollState(state: HlsPlaylistPoller.PollState, validators: Validators): HlsPlaylistPoller.PollState =
    state.copy(
      etag = validators.etag.orElse(state.etag)
    , lastModified = validators.lastModified.orElse(state.lastModified)
    )

  def headerValue(headers: Seq[HttpHeader], name: String): Option[String] =
    headers.find(_.lowercaseName() == name.toLowerCase).map(_.value())

  def normalizeEtag(value: String): String =
    value.stripPrefix("\"").stripSuffix("\"").stripPrefix("W/").stripPrefix("\"").stripSuffix("\"")

  def conditionalHeaders(validators: Validators): Seq[HttpHeader] = {
    val etagHeader = validators.etag.map(tag => `If-None-Match`(EntityTag(tag)))
    val modifiedHeader = validators.lastModified.map(value => RawHeader("If-Modified-Since", value))
    etagHeader.toSeq ++ modifiedHeader.toSeq
  }

  def extractValidators(headers: Seq[HttpHeader]): Validators =
    Validators(
      etag = headerValue(headers, "ETag").map(normalizeEtag)
    , lastModified = headerValue(headers, "Last-Modified")
    )
}