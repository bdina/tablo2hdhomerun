package app.config

import java.net.InetAddress

object ConfigTypes {
  opaque type HostAddress = InetAddress

  object HostAddress {
    def fromName(name: String): HostAddress = InetAddress.getByName(name)

    extension (addr: HostAddress) {
      def inet: InetAddress = addr
      def hostString: String = addr.getHostAddress
    }
  }

  opaque type Port = Int

  object Port {
    val DefaultTablo: Port = 8885
    val DefaultTabloFourthGen: Port = 8887
    val DefaultProxy: Port = 8080

    def apply(value: Int): Port = value

    extension (port: Port) {
      def value: Int = port
    }
  }

  opaque type HttpProtocol = String

  object HttpProtocol {
    val Http: HttpProtocol = "http"

    extension (protocol: HttpProtocol) {
      def value: String = protocol
    }
  }

  opaque type DeviceId <: String = String

  object DeviceId {
    val Default: DeviceId = "12345678"
    private val ChecksumTable: Array[Int] =
      Array(0xA, 0x5, 0xF, 0x6, 0x7, 0xC, 0x1, 0xB, 0x9, 0x2, 0x8, 0xD, 0x4, 0x3, 0xE, 0x0)

    def apply(value: String): DeviceId =
      if (value == null || value.trim.isEmpty) Default else value.trim

    extension (id: DeviceId) {
      inline def asString: String = id

      def toUnsignedInt: Option[Long] =
        if (id.length != 8) None
        else scala.util.Try(java.lang.Long.parseUnsignedLong(id, 16)).toOption

      def expectedCheckDigit: Option[Int] =
        toUnsignedInt.map { v =>
          ChecksumTable(((v >>> 28) & 0xF).toInt) ^
          ((v >>> 24) & 0xF).toInt ^
          ChecksumTable(((v >>> 20) & 0xF).toInt) ^
          ((v >>> 16) & 0xF).toInt ^
          ChecksumTable(((v >>> 12) & 0xF).toInt) ^
          ((v >>> 8) & 0xF).toInt ^
          ChecksumTable(((v >>> 4) & 0xF).toInt)
        }

      def hasValidSiliconDustChecksum: Boolean =
        toUnsignedInt.exists { v =>
          v != 0L && v != 0xFFFFFFFFL && expectedCheckDigit.contains((v & 0xF).toInt)
        }
    }
  }
}