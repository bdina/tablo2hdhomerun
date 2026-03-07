package app.tuner

import java.io.File
import java.net.InetAddress

import org.apache.pekko

import pekko.actor.typed.scaladsl.Behaviors
import pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.marshalling.Marshal
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.server.Directives._
import pekko.http.scaladsl.unmarshalling.Unmarshal
import pekko.stream.scaladsl.Source

import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, NodeSeq}

import spray.json._
import DefaultJsonProtocol._

import app.{AppContext, Tablo2HDHomeRun}
import app.stream.StreamBackend

object TabloLegacy {
  val log = Tablo2HDHomeRun.log
  implicit def system: ActorSystem[?] = Tablo2HDHomeRun.system
  implicit def ec: scala.concurrent.ExecutionContext = Tablo2HDHomeRun.ec
  lazy val HttpCtx = Http()

  object Response {
      object JsonProtocol {
        implicit object inetAddressFormat extends JsonFormat[InetAddress] {
          override def read(js : JsValue) : InetAddress = js match {
            case JsString(value) =>
              Try(InetAddress.getByName(value)) match {
                case Success(inetAddress) =>
                  inetAddress
                case Failure(t) =>
                  deserializationError(s"could not parse InetAddress -> ${t.getMessage}")
              }
            case _ =>
              deserializationError(s"Expected String for InetAddress, but got $js")
          }
          override def write(inetAddress : InetAddress) : JsValue = JsString(inetAddress.getHostAddress)
        }
        implicit object uriFormat extends JsonFormat[Uri] {
          override def read(js: JsValue): Uri = js match {
            case JsString(value) =>
              Try(Uri(value)) match {
                case Success(uri) =>
                  uri
                case Failure(t) =>
                  deserializationError(s"could not parse Uri -> ${t.getMessage}")
              }
            case _ =>
              deserializationError(s"Expected Uri path, but got $js")
          }
          override def write(uri : Uri) : JsValue = JsString(uri.toString)
        }
      }
      case class Discover(
        FriendlyName: String
      , LocalIP: InetAddress
      , BaseURL: Uri
      , LineupURL: Uri
      , Manufacturer: String = "tablo2hdhomerun"
      , ModelNumber: String = "HDHR3-US"
      , FirmwareName: String = "hdhomerun3_atsc"
      , FirmwareVersion: String = "20240101"
      , DeviceID: String = "12345678" // TODO hash friendly name
      , DeviceAuth: String = "tabloauth123"
      ) {
        def proxyAddress(inetAddress: InetAddress, port: Int) =
          BaseURL.withHost(inetAddress.getHostAddress.toString).withPort(port)
      }
      object Discover {
        def apply(friendlyName: String, localIp: InetAddress): Discover = {
          val proxyUri = Uri(s"${Tablo2HDHomeRun.TABLO_PROTOCOL}://${localIp.getHostAddress}:${Tablo2HDHomeRun.PROXY_PORT}")
          Discover(FriendlyName=friendlyName, LocalIP=localIp, BaseURL=proxyUri, LineupURL=proxyUri.withPath(Uri.Path("/lineup.json")))
        }
        object JsonProtocol {
          import Response.JsonProtocol.{inetAddressFormat,uriFormat}
          implicit val discoverFormat: JsonFormat[Discover] = jsonFormat10(Discover.apply)
        }

        val route =
          path("discover.json") {
            get {
              import Discover.JsonProtocol.discoverFormat
              val response = Tablo2HDHomeRun.discover.toJson
              log.info(s"[discover] discover.json (GET) - $response")
              complete(HttpEntity(ContentTypes.`application/json`, response.compactPrint))
            }
          }
      }
    }
    object Lineup {
      case class ChannelInfo(
        call_sign: String
      , call_sign_src: String
      , major: Int
      , minor: Int
      , network: Option[String]
      , resolution: String
      , favourite: Boolean
      , tms_station_id: String
      , tms_affiliate_id: String
      , source: String
      )

      case class ChannelObject(
        object_id: Int
      , path: String
      , channel: ChannelInfo
      )

      object JsonProtocol extends DefaultJsonProtocol {
        implicit val channelInfoFormat: JsonFormat[ChannelInfo] = jsonFormat10(ChannelInfo.apply)
        implicit val channelObjectFormat: JsonFormat[ChannelObject] = jsonFormat3(ChannelObject.apply)
      }

      object Proxy {
        object Request {
          val baseUrl = Tablo2HDHomeRun.discover.proxyAddress(Tablo2HDHomeRun.TABLO_IP,Tablo2HDHomeRun.TABLO_PORT)
          val getUri = baseUrl.withPath(Uri.Path("/guide/channels"))
          val postUri = baseUrl.withPath(Uri.Path("/batch"))

          val httpRequest = HttpRequest(uri = getUri)

          def postRequest(entity: RequestEntity) = HttpRequest(
            method = HttpMethods.POST
          , uri = postUri
          , entity = entity.withContentType(ContentTypes.`application/json`)
          )
        }
        object Response {
          object ChannelObject {
            def jsValue(obj: ChannelObject): JsValue = {
              val num = s"${obj.channel.major}.${obj.channel.minor}"
              val url = s"${Tablo2HDHomeRun.discover.BaseURL.withPath(Uri.Path(s"/channel/${obj.object_id}"))}"
              val src = s"${Tablo2HDHomeRun.discover.BaseURL.withPath(Uri.Path(s"/guide/channels/${obj.object_id}/watch"))}"
              JsObject(
                "GuideNumber" -> JsString(num)
              , "GuideName" -> JsString(obj.channel.call_sign)
              , "URL" -> JsString(url)
              , "type" -> JsString(obj.channel.source)
              , "srcURL" -> JsString(src)
              )
            }
          }
        }
      }

      object LineupActor {
        sealed trait Request
        object Request {
          case class Fetch(replyTo: ActorRef[Response.Fetch]) extends Request
          case class Status(replyTo: ActorRef[Response.Status]) extends Request
        }

        sealed trait Response
        object Response {
          case class Fetch(channels: Seq[JsValue], replyTo: ActorRef[Request.Fetch]) extends Response
          case class Status(scanInProgress: Int, scanPossible: Int, replyTo: ActorRef[Request.Fetch]) extends Response
        }

        sealed trait Command extends Request
        object Command {
          case class Store(channels: Seq[JsValue]) extends Command
          case object Scan extends Command
        }

        trait Error extends Exception
        object Error {
          case class ServerError(f: File) extends Exception(s"server error: $f") with Error
        }

        val log = LoggerFactory.getLogger(this.getClass)

        implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

        def apply(): Behavior[Request] = Behaviors.setup { context =>
          var cache: (scala.concurrent.duration.Deadline, Seq[JsValue]) = (0.seconds.fromNow, Seq.empty)
          var scanInProgress: Boolean = false

          val HttpCtx = Http()

          def scan(): Future[Seq[JsValue]] =
            HttpCtx
              .singleRequest(Proxy.Request.httpRequest)
              .flatMap { response =>
                log.info(s"[lineup-actor] guide/channels (GET) - $response")
                Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[String]])
              }
              .flatMap { paths =>
                import JsonProtocol._
                import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
                Marshal(paths.toJson)
                  .to[RequestEntity]
                  .flatMap { entity =>
                    HttpCtx.singleRequest(Proxy.Request.postRequest(entity)).flatMap { response =>
                      log.info(s"[lineup-actor] batch (POST) - $response")
                      Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Map[String, ChannelObject]])
                    }
                  }
              }
              .map { data =>
                log.info(s"[lineup-actor] channels found: ${data.size}")
                val channels =
                  data
                    .map { case (path,obj) =>
                      log.info(s"[lineup-actor] $path => ${obj.channel.call_sign}, ${obj.channel.network.getOrElse("None")}")
                      Proxy.Response.ChannelObject.jsValue(obj)
                    }
                    .toSeq

                context.self ! Command.Store(channels)

                channels
              }

          context.self ! Command.Scan

          Behaviors.receiveMessage {
            case Command.Store(channels) =>
              log.info(s"[lineup-actor] store channels: ${channels.size}")

              cache = (1.day.fromNow, channels)
              scanInProgress = false

              Behaviors.same

            case Command.Scan if scanInProgress =>
              log.info("[lineup-actor] channel scan requested ; already in progress (suppress)")

              Behaviors.same

            case Command.Scan =>
              log.info("[lineup-actor] channel scan requested")

              scanInProgress = true
              scan() : Unit

              Behaviors.same

            case Request.Status(sender) =>
              log.info("[lineup-actor] channel status requested")

              val (scanning,possible) = if (scanInProgress) (1,0) else (0,1)
              sender ! Response.Status(scanInProgress=scanning, scanPossible=possible, replyTo=context.self)

              Behaviors.same

            case Request.Fetch(replyTo) if cache._1.isOverdue() =>
              log.info("[lineup-actor] channel fetch")

              val sender = replyTo

              scanInProgress = true
              scan().foreach { channels =>
                sender ! LineupActor.Response.Fetch(channels, context.self)
              }

              Behaviors.same

            case Request.Fetch(sender) =>
              val (_,channels) = cache

              log.info(s"[lineup-actor] channels found: ${channels.size}")
              sender ! LineupActor.Response.Fetch(channels, context.self)

              Behaviors.same
          }
        }
      }

      object Response {
        case class LineupStatus(
          ScanInProgress: Int = 0
        , ScanPossible: Int = 1
        , Source: String = "Antenna"
        , SourceList: Seq[String] = List("Antenna")
        )
        object LineupStatus {
          object JsonProtocol {
            implicit val lineupStatusFormat: JsonFormat[LineupStatus] = jsonFormat4(LineupStatus.apply)
          }
        }
      }

      def route(lineupActor: ActorRef[Lineup.LineupActor.Request]) =
        path("lineup.json") {
          get {
            import pekko.actor.typed.scaladsl.AskPattern._
            implicit val timeout: pekko.util.Timeout = 3.seconds
            val lineupF: Future[LineupActor.Response.Fetch] = lineupActor.ask(replyTo => LineupActor.Request.Fetch(replyTo))

            onComplete(lineupF) {
              case Success(LineupActor.Response.Fetch(channels,_)) =>
                complete(HttpEntity(ContentTypes.`application/json`, channels.toJson.compactPrint))
              case Failure(ex) =>
                log.info(s"[lineup] Failed: ${ex.getMessage}")
                complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to produce channel lineup"))
            }
          }
        } ~
        path("lineup_status.json") {
          get {
            import pekko.actor.typed.scaladsl.AskPattern._
            implicit val timeout: pekko.util.Timeout = 3.seconds
            val statusF: Future[LineupActor.Response.Status] = lineupActor.ask(replyTo => LineupActor.Request.Status(replyTo))

            onComplete(statusF) {
              case Success(LineupActor.Response.Status(scanInProgress,scanPossible,_)) =>
                import Response.LineupStatus.JsonProtocol.lineupStatusFormat
                val response = Response.LineupStatus(ScanInProgress=scanInProgress,ScanPossible=scanPossible).toJson
                log.info(s"[lineup_status] lineup_status.json (GET) - $response")
                complete(HttpEntity(ContentTypes.`application/json`, response.compactPrint))
              case Failure(ex) =>
                log.info(s"[lineup_status] Failed: ${ex.getMessage}")
                complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to get lineup status"))
            }
          }
        }
    }

    object Guide {
      case class Program(
        id: String
      , title: String
      , description: Option[String]
      , start_time: String
      , end_time: String
      , channel_id: Int
      , episode_title: Option[String]
      , season_number: Option[Int]
      , episode_number: Option[Int]
      , year: Option[Int]
      , genre: Option[String]
      , rating: Option[String]
      , is_movie: Boolean = false
      , is_sports: Boolean = false
      , is_news: Boolean = false
      )

      case class ChannelGuide(
        channel_id: Int
      , call_sign: String
      , major: Int
      , minor: Int
      , programs: Seq[Program]
      )

      object JsonProtocol extends DefaultJsonProtocol {
        implicit val programFormat: JsonFormat[Program] = jsonFormat15(Program.apply)
        implicit val channelGuideFormat: JsonFormat[ChannelGuide] = jsonFormat5(ChannelGuide.apply)
      }

      object Proxy {
        object Request {
          val baseUrl = Tablo2HDHomeRun.discover.proxyAddress(Tablo2HDHomeRun.TABLO_IP,Tablo2HDHomeRun.TABLO_PORT)

          // try to discover program guide endpoints
          def getProgramsUri(channelId: Int, startTime: Option[String] = None, endTime: Option[String] = None) = {
            val baseUri = baseUrl.withPath(Uri.Path(s"/guide/channels/$channelId/programs"))
            val queryParams = (startTime, endTime) match {
              case (Some(start), Some(end)) => s"?start=$start&end=$end"
              case (Some(start), None) => s"?start=$start"
              case (None, Some(end)) => s"?end=$end"
              case _ => ""
            }
            Uri(baseUri.toString + queryParams)
          }

          def getScheduleUri(startTime: Option[String] = None, endTime: Option[String] = None) = {
            val baseUri = baseUrl.withPath(Uri.Path("/guide/schedule"))
            val queryParams = (startTime, endTime) match {
              case (Some(start), Some(end)) => s"?start=$start&end=$end"
              case (Some(start), None) => s"?start=$start"
              case (None, Some(end)) => s"?end=$end"
              case _ => ""
            }
            Uri(baseUri.toString + queryParams)
          }

          def httpRequest(uri: Uri) = HttpRequest(uri = uri)
        }

        object Response {
          // try to parse program data from various possible Tablo API response formats
          case class TabloProgram(
            id: Option[String]
          , title: Option[String]
          , description: Option[String]
          , start_time: Option[String]
          , end_time: Option[String]
          , episode_title: Option[String]
          , season_number: Option[Int]
          , episode_number: Option[Int]
          , year: Option[Int]
          , genre: Option[String]
          , rating: Option[String]
          , is_movie: Option[Boolean]
          , is_sports: Option[Boolean]
          , is_news: Option[Boolean]
          )

          object TabloProgram {
            object JsonProtocol {
              implicit val tabloProgramFormat: JsonFormat[TabloProgram] = jsonFormat14(TabloProgram.apply)
            }
          }

          def convertToProgram(tabloProgram: TabloProgram, channelId: Int): Program = {
            Program(
              id = tabloProgram.id.getOrElse(s"prog_${System.currentTimeMillis()}")
            , title = tabloProgram.title.getOrElse("Unknown Program")
            , description = tabloProgram.description
            , start_time = tabloProgram.start_time.getOrElse("")
            , end_time = tabloProgram.end_time.getOrElse("")
            , channel_id = channelId
            , episode_title = tabloProgram.episode_title
            , season_number = tabloProgram.season_number
            , episode_number = tabloProgram.episode_number
            , year = tabloProgram.year
            , genre = tabloProgram.genre
            , rating = tabloProgram.rating
            , is_movie = tabloProgram.is_movie.getOrElse(false)
            , is_sports = tabloProgram.is_sports.getOrElse(false)
            , is_news = tabloProgram.is_news.getOrElse(false)
            )
          }
        }
      }

    object GuideActor {
      sealed trait Request
      object Request {
        case class FetchGuide(replyTo: ActorRef[Response.FetchGuide]) extends Request
        case class FetchChannelGuide(channelId: Int, replyTo: ActorRef[Response.FetchChannelGuide]) extends Request
      }

      sealed trait Response
      object Response {
        case class FetchGuide(guide: Seq[ChannelGuide], replyTo: ActorRef[Request.FetchGuide]) extends Response
        case class FetchChannelGuide(channelGuide: ChannelGuide, replyTo: ActorRef[Request.FetchChannelGuide]) extends Response
      }

      sealed trait Command extends Request
      object Command {
        case class StoreGuide(guide: Seq[ChannelGuide]) extends Command
        case object Scan extends Command
      }

      val log = LoggerFactory.getLogger(this.getClass)

      implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

      // Singleton actor instance to avoid creating new actors per request
      private var _instance: Option[ActorRef[Request]] = None

      def instance: ActorRef[Request] = _instance.getOrElse {
        val actor = AppContext.system.systemActorOf(apply(), "guide-actor-singleton", pekko.actor.typed.Props.empty)
        _instance = Some(actor)
        actor
      }

      def apply(): Behavior[Request] = Behaviors.setup { context =>
        var cache: (scala.concurrent.duration.Deadline, Seq[ChannelGuide]) = (0.seconds.fromNow, Seq.empty)
        var scanInProgress: Boolean = false

        val HttpCtx = Http()

        def generateFallbackPrograms(channelId: Int, callSign: String): Seq[Program] = {
          // Generate sample program data when Tablo API doesn't provide it
          val now = java.time.Instant.now()

          // Use LazyList for memory efficiency - only materialize when needed
          LazyList.range(0, 24).map { hour =>
            val startTime = now.plusSeconds(hour * 3600)
            val endTime = startTime.plusSeconds(3600) // 1 hour programs

            val programTitle = hour match {
              case 0 | 1 | 2 | 3 | 4 | 5 => "Late Night Programming"
              case 6 | 7 | 8 | 9 => "Morning News"
              case 10 | 11 | 12 | 13 => "Daytime Programming"
              case 14 | 15 | 16 | 17 => "Afternoon Shows"
              case 18 | 19 | 20 | 21 => "Prime Time"
              case 22 | 23 => "Evening News"
            }

            val description = s"Programming on $callSign"
            val genre = hour match {
              case 6 | 7 | 8 | 9 | 22 | 23 => Some("News")
              case 18 | 19 | 20 | 21 => Some("Drama")
              case _ => Some("General")
            }

            Program(
              id = s"fallback_${channelId}_${hour}",
              title = programTitle,
              description = Some(description),
              start_time = startTime.toString,
              end_time = endTime.toString,
              channel_id = channelId,
              episode_title = None,
              season_number = None,
              episode_number = None,
              year = None,
              genre = genre,
              rating = None,
              is_movie = false,
              is_sports = false,
              is_news = hour match { case 6 | 7 | 8 | 9 | 22 | 23 => true; case _ => false }
            )
          }.toSeq
        }

        def fetchProgramsForChannel(channelId: Int): Future[Seq[Program]] = {
          // try multiple possible endpoints for program data
          val endpoints = Seq(
            Proxy.Request.getProgramsUri(channelId),
            Proxy.Request.getScheduleUri().withPath(Uri.Path(s"/guide/channels/$channelId")),
            Proxy.Request.baseUrl.withPath(Uri.Path(s"/guide/channels/$channelId/schedule"))
          )

          def tryEndpoint(uri: Uri): Future[Seq[Program]] = {
            HttpCtx.singleRequest(Proxy.Request.httpRequest(uri)).flatMap { response =>
              log.info(s"[guide-actor] trying endpoint $uri - $response")
              if (response.status.isSuccess()) {
                Unmarshal(response.entity).to[String].map { body =>
                  log.info(s"[guide-actor] response body: $body")
                  Try { // try to parse as JSON array of programs
                    import Proxy.Response.TabloProgram.JsonProtocol.tabloProgramFormat
                    body
                      .parseJson
                      .convertTo[Seq[Proxy.Response.TabloProgram]]
                      .map(Proxy.Response.convertToProgram(_, channelId))
                  }
                  .getOrElse {
                    Try { // if that fails, try to parse as a single program object
                      import Proxy.Response.TabloProgram.JsonProtocol.tabloProgramFormat
                      val program = body.parseJson.convertTo[Proxy.Response.TabloProgram]
                      Seq(Proxy.Response.convertToProgram(program, channelId))
                    }
                    .getOrElse {
                      log.warn(s"[guide-actor] could not parse program data from $uri")
                      Seq.empty
                    }
                  }
                }
              } else {
                log.info(s"[guide-actor] endpoint $uri returned ${response.status}")
                Future.successful(Seq.empty)
              }
            }
            .recover {
              case ex =>
                log.warn(s"[guide-actor] failed to fetch from $uri: ${ex.getMessage}")
                Seq.empty
            }
          }

          // Try endpoints in sequence until one succeeds
          endpoints.foldLeft(Future.successful(Seq.empty[Program])) { (acc, uri) =>
            acc.flatMap { programs =>
              if (programs.nonEmpty) {
                Future.successful(programs)
              } else {
                tryEndpoint(uri)
              }
            }
          }
          .map { programs =>
            if (programs.isEmpty) {
              log.info(s"[guide-actor] no programs found for channel $channelId, generating fallback data")
              generateFallbackPrograms(channelId, "Unknown")
            } else {
              programs
            }
          }
        }

        def scan(): Future[Seq[ChannelGuide]] = {
          // First get the list of channels
          val channelsUri = Proxy.Request.baseUrl.withPath(Uri.Path("/guide/channels"))
          HttpCtx.singleRequest(Proxy.Request.httpRequest(channelsUri)).flatMap { response =>
            log.info(s"[guide-actor] guide/channels (GET) - $response")
            if (response.status.isSuccess()) {
              Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[String]]).flatMap { paths =>
                import Lineup.JsonProtocol._
                import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
                Marshal(paths.toJson)
                  .to[RequestEntity]
                  .flatMap { entity =>
                    val batchUri = Proxy.Request.baseUrl.withPath(Uri.Path("/batch"))
                    val batchRequest = HttpRequest(
                      method = HttpMethods.POST,
                      uri = batchUri,
                      entity = entity.withContentType(ContentTypes.`application/json`)
                    )
                    HttpCtx.singleRequest(batchRequest).flatMap { batchResponse =>
                      log.info(s"[guide-actor] batch (POST) - $batchResponse")
                      Unmarshal(batchResponse.entity).to[String].map(_.parseJson.convertTo[Map[String, Lineup.ChannelObject]])
                    }
                  }
              }.flatMap { channelData =>
                log.info(s"[guide-actor] channels found: ${channelData.size}")

                // fetch programs for each channel
                val channelFutures = channelData.map { case (_, channelObj) =>
                  fetchProgramsForChannel(channelObj.object_id).map { programs =>
                    ChannelGuide(
                      channel_id = channelObj.object_id,
                      call_sign = channelObj.channel.call_sign,
                      major = channelObj.channel.major,
                      minor = channelObj.channel.minor,
                      programs = programs
                    )
                  }
                }

                Future.sequence(channelFutures.toSeq)
              }
            } else {
              log.warn(s"[guide-actor] failed to get channels: ${response.status}")
              Future.successful(Seq.empty)
            }
          }
          .recover {
            case ex =>
              log.error(s"[guide-actor] scan failed: ${ex.getMessage}")
              Seq.empty
          }
        }

        context.self ! Command.Scan

        Behaviors.receiveMessage {
          case Command.StoreGuide(guide) =>
            log.info(s"[guide-actor] store guide: ${guide.size} channels")
            cache = (1.hour.fromNow, guide)
            scanInProgress = false
            Behaviors.same

          case Command.Scan if scanInProgress =>
            log.info("[guide-actor] guide scan requested ; already in progress (suppress)")
            Behaviors.same

          case Command.Scan =>
            log.info("[guide-actor] guide scan requested")
            scanInProgress = true
            scan().foreach { guide =>
              context.self ! Command.StoreGuide(guide)
            }
            Behaviors.same

          case Request.FetchGuide(replyTo) if cache._1.isOverdue() =>
            log.info("[guide-actor] guide fetch - cache expired")
            scanInProgress = true
            scan().foreach { guide =>
              replyTo ! Response.FetchGuide(guide, context.self)
            }
            Behaviors.same

          case Request.FetchGuide(replyTo) =>
            val (_, guide) = cache
            log.info(s"[guide-actor] guide fetch from cache: ${guide.size} channels")
            replyTo ! Response.FetchGuide(guide, context.self)
            Behaviors.same

          case Request.FetchChannelGuide(channelId, replyTo) =>
            val (_, guide) = cache
            val channelGuide = guide.find(_.channel_id == channelId).getOrElse {
              ChannelGuide(channelId, "Unknown", 0, 0, Seq.empty)
            }
            replyTo ! Response.FetchChannelGuide(channelGuide, context.self)
            Behaviors.same
        }
        }
      }

      object XMLTVFormatter {
        def formatTimestamp(timeStr: String): String = {
          Try { // try to parse various timestamp formats and convert to XMLTV format
            val instant = java.time.Instant.parse(timeStr)
            instant.toString
          }
          .getOrElse {
            Try {
              val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
              val localDateTime = java.time.LocalDateTime.parse(timeStr, formatter)
              localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant.toString
            }
            .getOrElse {
              java.time.Instant.now().toString
            }
          }
        }

        def formatChannel(channelGuide: ChannelGuide): Elem = {
          val channelId = s"${channelGuide.major}.${channelGuide.minor}"
          <channel id={channelId}>
            <display-name>{channelGuide.call_sign}</display-name>
            <display-name>{channelId}</display-name>
            <display-name>{s"${channelGuide.call_sign} (${channelId})"}</display-name>
          </channel>
        }

        def formatProgram(program: Program, channelGuide: ChannelGuide): Elem = {
          val channelId = s"${channelGuide.major}.${channelGuide.minor}"
          val startTime = formatTimestamp(program.start_time)
          val stopTime = formatTimestamp(program.end_time)

          <programme start={startTime} stop={stopTime} channel={channelId}>
            <title>{program.title}</title>
            {program.episode_title.map(ep => <sub-title>{ep}</sub-title>).getOrElse(NodeSeq.Empty)}
            {program.description.map(desc => <desc>{desc}</desc>).getOrElse(NodeSeq.Empty)}
            {program.year.map(year => <date>{year}</date>).getOrElse(NodeSeq.Empty)}
            {program.genre.map(genre => <category>{genre}</category>).getOrElse(NodeSeq.Empty)}
            {program.rating.map(rating => <rating><value>{rating}</value></rating>).getOrElse(NodeSeq.Empty)}
            {if (program.is_movie) <category>Movie</category> else NodeSeq.Empty}
            {if (program.is_sports) <category>Sports</category> else NodeSeq.Empty}
            {if (program.is_news) <category>News</category> else NodeSeq.Empty}
          </programme>
        }

        def formatGuide(guide: Seq[ChannelGuide]): Elem = {
          val channels = guide.map(formatChannel)
          val programmes = guide.flatMap(channelGuide =>
            channelGuide.programs.map(program => formatProgram(program, channelGuide))
          )

          <tv>
            {channels}
            {programmes}
          </tv>
        }
      }

      val route =
        path("guide.xml") {
          get {
            import pekko.actor.typed.scaladsl.AskPattern._
            implicit val timeout: pekko.util.Timeout = 10.seconds

            val guideF: Future[GuideActor.Response.FetchGuide] =
              GuideActor.instance.ask(replyTo => GuideActor.Request.FetchGuide(replyTo))

            onComplete(guideF) {
              case Success(GuideActor.Response.FetchGuide(guide, _)) =>
                log.info(s"[guide] guide.xml (GET) - ${guide.size} channels")

                // stream XML generation to avoid loading entire response in memory
                import pekko.util.ByteString
                val xmlStream =
                  Source
                    .fromIterator { () =>
                      Iterator("<tv>") ++
                      guide.iterator.flatMap(channelGuide =>
                        Iterator(XMLTVFormatter.formatChannel(channelGuide).toString) ++
                        channelGuide.programs.iterator.map(program =>
                          XMLTVFormatter.formatProgram(program, channelGuide).toString
                        )
                      ) ++
                      Iterator("</tv>")
                    }
                    .map(ByteString(_))

                complete(HttpEntity.Chunked.fromData(ContentTypes.`text/xml(UTF-8)`, xmlStream))
              case Failure(ex) =>
                log.info(s"[guide] Failed: ${ex.getMessage}")
                val emptyGuide = <tv></tv> // return empty XMLTV format on failure
                complete(HttpEntity(ContentTypes.`text/xml(UTF-8)`, emptyGuide.toString))
            }
          }
        }
    }

    object Channel {
      object Request {
        case class WatchRequest(
          bandwidth: Int = 1000
        , no_fast_startup: Boolean = false
        )
        object WatchRequest {
          object JsonProtocol {
            implicit val watchRequestFormat: JsonFormat[WatchRequest] = jsonFormat2(WatchRequest.apply)
          }
          import JsonProtocol.watchRequestFormat
          val watchRequestJson = Request.WatchRequest().toJson
          def httpRequest(uri: Uri) = HttpRequest(
            method = HttpMethods.POST
          , uri = uri
          , entity = HttpEntity(ContentTypes.`application/json`, watchRequestJson.compactPrint)
          )
        }
        object Tuners {
          val uri = Tablo2HDHomeRun.discover.proxyAddress(Tablo2HDHomeRun.TABLO_IP,Tablo2HDHomeRun.TABLO_PORT).withPath(Uri.Path(s"/server/tuners"))
          val httpRequest = HttpRequest(uri = uri)
        }
      }
      object Response {
        case class VideoDetails(width: Int = 0, height: Int = 0)
        case class WatchResponse(
          token: String // df9586a4-5548-48a9-87f2-1191a8a0df8b
        , expires: String // 2025-06-18T02:55:38Z
        , keepalive: Int // 120
        , playlist_url: Uri // http://192.168.11.219:80/stream/pl.m3u8?-_XhOSWWP5qBhXIVMu6c7g
        , bif_url_sd: Option[Uri]
        , bif_url_hd: Option[Uri]
        , video_details: VideoDetails
        )
        case object NoAvailableTunersError extends Exception("No available tuners")
        object JsonProtocol {
          import TabloLegacy.Response.JsonProtocol.uriFormat
          implicit val videoDetailsFormat: JsonFormat[VideoDetails] = jsonFormat2(VideoDetails.apply)
          implicit val watchResponseFormat: JsonFormat[WatchResponse] = jsonFormat7(WatchResponse.apply)
        }

        case class Tuners(in_use: Boolean, channel: Option[Uri], recording: Option[Uri])
        object Tuners {
          object JsonProtocol {
            import TabloLegacy.Response.JsonProtocol.uriFormat
            implicit val tunersFormat: JsonFormat[Tuners] = jsonFormat3(Tuners.apply)
          }
        }
      }

      val route =
        path("channel" / LongNumber) { channelId =>
          get {
            import Response.JsonProtocol._

            val watchUri =
              Tablo2HDHomeRun.discover
                .proxyAddress(Tablo2HDHomeRun.TABLO_IP,Tablo2HDHomeRun.TABLO_PORT)
                .withPath(Uri.Path(s"/guide/channels/$channelId/watch"))

            val tunersFuture: Future[Seq[Response.Tuners]] =
              HttpCtx.singleRequest(Request.Tuners.httpRequest).flatMap { response =>
                import Response.Tuners.JsonProtocol.tunersFormat
                log.info(s"[channel] server/tuners (POST) - $response")
                Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[Response.Tuners]])
              }

            val watchFuture: Future[Response.WatchResponse] = tunersFuture.flatMap { tuners =>
              val available = tuners.filterNot(_.in_use).size
              log.info(s"[channel] available tuners - $available")
              if (available > 0) {
                HttpCtx.singleRequest(Request.WatchRequest.httpRequest(watchUri)).flatMap { response =>
                  log.info(s"[channel] guide/channels/$channelId/watch (POST) - $response")
                  Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Response.WatchResponse])
                }
              } else {
                log.info(s"[channel] no available tuners (0/${tuners.size})")
                Future.failed(Response.NoAvailableTunersError)
              }
            }

            onComplete (watchFuture) {
              case Success(data) =>
                log.info(s"[channel] tuned to channel - $data")
                val `video/mp2t` = MediaType.customBinary("video", "mp2t", MediaType.NotCompressible)
                complete(HttpEntity.Chunked.fromData(ContentType(`video/mp2t`), StreamBackend().stream(data.playlist_url.toString)))
              case Failure(ex) =>
                log.info(s"[channel] failed to start stream - ${ex.getMessage}")
                complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to stream channel"))
            }
          }
        }
    }

    object Favicon {
      val route =
        path("favicon.ico") {
          get {
            log.info("[favicon] favicon.ico (no-op) (GET)")
            complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, ""))
          }
        }
    }
}
