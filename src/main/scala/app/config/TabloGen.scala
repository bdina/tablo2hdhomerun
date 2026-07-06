package app.config

enum TabloGen {
  case FourthGen
  case Legacy

  def isFourthGen: Boolean = this == FourthGen

  def envValue: String = this match {
    case FourthGen => "4thgen"
    case Legacy => "legacy"
  }

  def discoverFriendlyName: String = this match {
    case FourthGen => "Tablo 4th Gen Proxy"
    case Legacy => "Tablo Legacy Gen Proxy"
  }

  def startupModeText: String = this match {
    case FourthGen => " (4th Gen mode)"
    case Legacy => "(legacy mode)"
  }
}

object TabloGen {
  def fromEnv(value: String): TabloGen = value match {
    case "4thgen" => TabloGen.FourthGen
    case _ => TabloGen.Legacy
  }
}
