package app.sys

import scala.util.Try

import spray.json._

/** Safe JSON decoding helpers wrapping Spray-JSON deserialization into Try values. */
object SafeJson {
  def parseAs[T: JsonReader](body: String): Try[T] =
    Try(Option(body).getOrElse("").trim.parseJson.convertTo[T])
}
