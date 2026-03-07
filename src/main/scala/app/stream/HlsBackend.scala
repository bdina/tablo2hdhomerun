package app.stream

import org.apache.pekko

import pekko.actor.typed.ActorSystem
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.HttpRequest
import pekko.stream.scaladsl.Source
import pekko.util.ByteString

import org.apache.pekko.pattern.after
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HlsBackend extends StreamBackend {
  val log = LoggerFactory.getLogger(this.getClass)
  override def name: String = "hls"
  val defaultPollSec: Int = 2

  override def stream(playlistUrl: String)(implicit system: ActorSystem[?]): Source[ByteString, ?] = {
    import org.apache.pekko.actor.typed.scaladsl.adapter._
    implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
    val mat: pekko.stream.Materializer = pekko.stream.SystemMaterializer(system).materializer
    val http = Http(system.toClassic)
    def fetchPlaylistBody(url: String)(implicit mat: pekko.stream.Materializer): Future[String] = {
      http.singleRequest(HttpRequest(uri = url)).flatMap { response =>
        if (response.status.isSuccess()) {
          pekko.http.scaladsl.unmarshalling.Unmarshal(response.entity).to[String]
        } else {
          val _ = response.entity.discardBytes()
          Future.failed(new RuntimeException(s"playlist fetch failed: ${response.status}"))
        }
      }
    }
    def fetchSegmentSource(url: String, byteRange: Option[(Long, Long)]): Source[ByteString, ?] = {
      val request = byteRange match {
        case Some((offset, length)) =>
          HttpRequest(uri = url).addHeader(
            pekko.http.scaladsl.model.headers.Range(
              pekko.http.scaladsl.model.headers.ByteRange(offset, offset + length - 1)
            )
          )
        case None => HttpRequest(uri = url)
      }
      Source.futureSource(
        http.singleRequest(request).map { response =>
          if (response.status.isSuccess()) response.entity.dataBytes
          else {
            val _ = response.entity.discardBytes()
            Source.failed(new RuntimeException(s"segment fetch failed: ${response.status}"))
          }
        }
      ).mapMaterializedValue(_ => pekko.NotUsed)
    }
    def resolveMediaPlaylistUrl(url: String)(implicit mat: pekko.stream.Materializer): Future[String] = {
      fetchPlaylistBody(url).flatMap { body =>
        val lines = body.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
        val isMaster = lines.exists(_.startsWith("#EXT-X-STREAM-INF"))
        if (isMaster) {
          val variantUriOpt = lines.zipWithIndex.find(_._1.startsWith("#EXT-X-STREAM-INF")).flatMap { case (_, i) =>
            if (i + 1 < lines.length) {
              val next = lines(i + 1).trim
              if (!next.startsWith("#") && next.nonEmpty) Some(next) else None
            } else None
          }
          variantUriOpt match {
            case Some(relative) =>
              val resolved = M3U8.resolveSegmentUri(relative, url)
              log.info(s"[stream:hls] master playlist -> variant: $resolved")
              resolveMediaPlaylistUrl(resolved)(mat)
            case None => Future.successful(url)
          }
        } else {
          val playlist = M3U8.parse(body)
          if (playlist.segments.nonEmpty) {
            Future.successful(url)
          } else {
            val m3u8Line = lines.find(line => !line.startsWith("#") && line.contains(".m3u8"))
            m3u8Line match {
              case Some(relative) =>
                val resolved = M3U8.resolveSegmentUri(relative, url)
                fetchPlaylistBody(resolved)(mat).map(_ => resolved)
              case None => Future.successful(url)
            }
          }
        }
      }
    }
    val maxConsecutiveFailures = 10
    type SegmentInfo = (String, Option[(Long, Long)])
    type State = (String, Int, Boolean, Int, Int, Boolean)
    def pollIntervalSecFromTarget(targetSec: Int): Int = (targetSec / 2).max(1)
    def step(state: State)(implicit mat: pekko.stream.Materializer): Future[Option[(State, Seq[SegmentInfo])]] = {
      val (baseUrl, lastSeq, done, lastTargetDuration, failures, hasLoggedFirstSegment) = state
      if (done) {
        Future.successful(None)
      } else {
        val doFetch: () => Future[Option[(State, Seq[SegmentInfo])]] = () => {
          fetchPlaylistBody(baseUrl).map { body =>
            val playlist = M3U8.parse(body)
            val isFirstPoll = lastSeq == 0
            val startIndex = if (isFirstPoll && !playlist.isEndList)
              (playlist.segments.size - 3).max(0)
            else
              (lastSeq - playlist.mediaSequence).max(0)
            val toEmit = playlist.segments.drop(startIndex)
            val segmentInfos = toEmit.map { seg =>
              (M3U8.resolveSegmentUri(seg.uri, baseUrl), seg.byteRange)
            }
            if (segmentInfos.nonEmpty && !hasLoggedFirstSegment) {
              val rangeDesc = segmentInfos.head._2.map { case (o, l) => s" range=$o-${o + l - 1}" }.getOrElse("")
              log.info(s"[stream:hls] streaming ${segmentInfos.size} segments (skipped $startIndex)," +
                s" first: ${segmentInfos.head._1}$rangeDesc")
            }
            val newLastSeq = playlist.mediaSequence + playlist.segments.size
            val nowDone = playlist.isEndList
            val nextTarget = if (playlist.targetDuration > 0) playlist.targetDuration else defaultPollSec
            val nextState: State = (baseUrl, newLastSeq, nowDone, nextTarget, 0, hasLoggedFirstSegment || segmentInfos.nonEmpty)
            Some((nextState, segmentInfos.toSeq))
          }.recover { case ex =>
            log.warn(s"[stream:hls] step failed: ${ex.getMessage}, retrying next poll")
            if (failures + 1 >= maxConsecutiveFailures) {
              log.error(s"[stream:hls] exceeded $maxConsecutiveFailures consecutive failures, ending stream")
              None
            } else Some(((baseUrl, lastSeq, done, lastTargetDuration, failures + 1, hasLoggedFirstSegment), Seq.empty))
          }
        }
        val scheduler: pekko.actor.Scheduler = system.toClassic.scheduler
        val delaySec = if (lastSeq == 0) 0 else pollIntervalSecFromTarget(if (lastTargetDuration > 0) lastTargetDuration else defaultPollSec)
        if (delaySec <= 0) {
          doFetch()
        } else {
          val delay = delaySec.seconds
          after(delay, scheduler)(Future.successful(())).flatMap(_ => doFetch())
        }
      }
    }
    Source.lazySource { () =>
      log.info(s"[stream:hls] stream materialized, resolving: $playlistUrl")
      val inner = Source.futureSource(
        resolveMediaPlaylistUrl(playlistUrl)(mat).map { url =>
          log.info(s"[stream:hls] resolved media playlist: $url")
          Source.unfoldAsync((url, 0, false, 0, 0, false): State)(s => step(s)(mat)).flatMapConcat {
            (segments: Seq[SegmentInfo]) =>
              if (segments.isEmpty) Source.empty
              else Source(segments).flatMapConcat { case (u, br) => fetchSegmentSource(u, br) }
          }
        }
      )
      inner.watchTermination() { (_, done) =>
        done.onComplete {
          case Success(_) => log.info("[stream:hls] stream completed")
          case Failure(ex) => log.info(s"[stream:hls] stream failed: ${ex.getMessage}")
        }
      }
    }
  }
}
