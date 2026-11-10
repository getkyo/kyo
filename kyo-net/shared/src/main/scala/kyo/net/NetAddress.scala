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

    /** Resolve `host` to its 4 (IPv4) or 16 (IPv6) address bytes in network order.
      *
      * It takes the connect's own path: a literal and a loopback name (`localhost`, `ip6-localhost`, `ip6-loopback`) answer without a lookup,
      * and any other name uses the JVM and Native transports' resolver and cache. Resolution does not pin a later connection: cached entries
      * can expire or be evicted, and concurrent lookups can return different answers. On JS and Wasm, non-loopback names fail with
      * `NetDnsResolutionException`; literals and loopback names work on every platform.
      */
    def resolve(host: String)(using AllowUnsafe, Frame): Fiber.Unsafe[Span[Byte], Abort[NetDnsResolutionException]] =
        val name = SockAddr.resolveLoopbackName(host)
        SockAddr.parseIpv4(name).orElse(SockAddr.parseIpv6(name)).fold {
            val familyHint = if host.contains(':') then PosixConstants.AF_INET6 else PosixConstants.AF_INET
            HostResolver.resolve(host, familyHint).map(resolved => Span.from(resolved.addr))
        }(literal => Fiber.Unsafe.fromResult(Result.succeed(Span.from(literal))))
    end resolve

end NetAddress
