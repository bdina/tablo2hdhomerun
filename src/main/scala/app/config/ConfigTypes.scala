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
}