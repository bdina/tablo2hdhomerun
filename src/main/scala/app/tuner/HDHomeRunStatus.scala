package app.tuner

import app.stream.StreamMetrics
import spray.json._

object HDHomeRunStatus extends DefaultJsonProtocol {

  final case class TunerStatus(
    Resource: String
  , VctNumber: Option[String] = None
  , VctName: Option[String] = None
  , SignalStrengthPercent: Option[Int] = None
  , SignalQualityPercent: Option[Int] = None
  , SymbolQualityPercent: Option[Int] = None
  , NetworkRate: Option[Long] = None
  , TargetIP: Option[String] = None
  )

  final case class ActiveTunerInfo(
    channelId: String
  , vctNumber: String
  , vctName: String
  , snapshot: StreamMetrics.WindowSnapshot
  , targetIp: Option[String] = None
  )

  implicit val tunerStatusFormat: RootJsonFormat[TunerStatus] = new RootJsonFormat[TunerStatus] {
    override def write(s: TunerStatus): JsValue = {
      val fields = Vector.newBuilder[(String, JsValue)]
      fields += ("Resource" -> JsString(s.Resource))
      s.VctNumber.foreach(v => fields += ("VctNumber" -> JsString(v)))
      s.VctName.foreach(v => fields += ("VctName" -> JsString(v)))
      s.SignalStrengthPercent.foreach(v => fields += ("SignalStrengthPercent" -> JsNumber(v)))
      s.SignalQualityPercent.foreach(v => fields += ("SignalQualityPercent" -> JsNumber(v)))
      s.SymbolQualityPercent.foreach(v => fields += ("SymbolQualityPercent" -> JsNumber(v)))
      s.NetworkRate.foreach(v => fields += ("NetworkRate" -> JsNumber(v)))
      s.TargetIP.foreach(v => fields += ("TargetIP" -> JsString(v)))
      JsObject(fields.result()*)
    }

    override def read(json: JsValue): TunerStatus = {
      val obj = json.asJsObject.fields
      def optStr(k: String): Option[String] = obj.get(k).collect { case JsString(v) => v }
      def optInt(k: String): Option[Int] = obj.get(k).collect { case JsNumber(v) => v.toInt }
      def optLong(k: String): Option[Long] = obj.get(k).collect { case JsNumber(v) => v.toLong }
      TunerStatus(
        Resource = optStr("Resource").getOrElse("tuner0")
      , VctNumber = optStr("VctNumber")
      , VctName = optStr("VctName")
      , SignalStrengthPercent = optInt("SignalStrengthPercent")
      , SignalQualityPercent = optInt("SignalQualityPercent")
      , SymbolQualityPercent = optInt("SymbolQualityPercent")
      , NetworkRate = optLong("NetworkRate")
      , TargetIP = optStr("TargetIP")
      )
    }
  }

  def buildTunerStatuses(totalTuners: Int, active: Seq[ActiveTunerInfo]): Vector[TunerStatus] = {
    val count = math.max(1, math.max(totalTuners, active.size))
    val sortedActive = active.sortBy(_.channelId)
    (0 until count).toVector.map { idx =>
      val resource = s"tuner$idx"
      if (idx < sortedActive.size) {
        val info = sortedActive(idx)
        val snap = info.snapshot
        TunerStatus(
          Resource = resource
        , VctNumber = Some(info.vctNumber)
        , VctName = Some(info.vctName)
        , SignalStrengthPercent = Some(snap.signalStrengthPercentInt)
        , SignalQualityPercent = Some(snap.signalQualityPercentInt)
        , SymbolQualityPercent = Some(snap.symbolQualityPercentInt)
        , NetworkRate = Some(snap.networkRateBps)
        , TargetIP = info.targetIp.orElse(Some("http"))
        )
      } else {
        TunerStatus(Resource = resource)
      }
    }
  }

  def buildStatusArray(totalTuners: Int, active: Seq[ActiveTunerInfo]): JsArray =
    JsArray(buildTunerStatuses(totalTuners, active).map(_.toJson))
}

