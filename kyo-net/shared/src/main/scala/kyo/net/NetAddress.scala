package kyo.net

import kyo.*
import kyo.net.internal.posix.HostResolver
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.SockAddr

/** A network address: either a TCP `(host, port)` pair or a Unix domain socket path.
  *
  * Used by both client and server:
  *   - Client: identifies the connection pool key and transport target for each unique remote host.
  *   - Server: returned by listeners to report the address the server actually bound to.
  *
  * Unix socket addresses are selected by setting `unixSocket` on config or using `http+unix://` scheme URLs on the client.
  */
enum NetAddress derives CanEqual:
    case Tcp(host: String, port: Int)
    case Unix(path: String)
end NetAddress

object NetAddress:

    /** The IP address a connect to `host` reaches, as its 4 (IPv4) or 16 (IPv6) bytes in network order.
      *
      * It takes the connect's own path: a literal and a loopback name (`localhost`, `ip6-localhost`, `ip6-loopback`) answer without a lookup,
      * and any other name goes through the resolver the JVM and Native transports connect with, sharing its cache, so a connect within
      * `kyo.net.dnsTtl` of this answer reaches the same address. On JS, where Node resolves inside its own connect, a name fails with
      * `NetDnsResolutionException`.
      */
    def resolve(host: String)(using AllowUnsafe, Frame): Fiber.Unsafe[Span[Byte], Abort[NetDnsResolutionException]] =
        val name = SockAddr.resolveLoopbackName(host)
        SockAddr.parseIpv4(name).orElse(SockAddr.parseIpv6(name)).fold {
            val familyHint = if host.contains(':') then PosixConstants.AF_INET6 else PosixConstants.AF_INET
            HostResolver.resolve(host, familyHint).map(resolved => Span.from(resolved.addr))
        }(literal => Fiber.Unsafe.fromResult(Result.succeed(Span.from(literal))))
    end resolve

end NetAddress
