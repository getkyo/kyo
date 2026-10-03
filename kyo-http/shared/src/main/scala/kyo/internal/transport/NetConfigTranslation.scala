package kyo.internal.transport

import kyo.*
import kyo.net.NetAddress
import kyo.net.NetConfig
import kyo.net.NetConfigException
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Transport

/** Translation seam between kyo-http's public config/address vocabulary and kyo-net's internal transport types.
  *
  * It is the only place a `kyo.net.*` config/address type appears in kyo-http; the `kyo.net.NetTlsConfig` / `kyo.net.NetAddress` types never
  * escape into a `kyo.Http*` public signature. `toNetTlsConfig` copies the 8 fields shared with `HttpTlsConfig` by name and leaves the 2
  * kyo-net-only fields (`caCertPath`, `hostnameVerification`) at their defaults, since `HttpTlsConfig` has no field for them. `toHttpAddress`
  * maps the structurally identical address enum case-for-case so `HttpServer.address` keeps returning `HttpAddress`.
  *
  * kyo-net takes a checked channel capacity and checked deadlines, and kyo-http's transport settings are raw values, so the config
  * translators return a `Result`.
  * [[connect]] and [[listen]] are the only callers: a value kyo-net refuses fails the operation's fiber with its [[NetConfigException]], on
  * the same `Abort[NetException]` row every other transport failure takes.
  */
private[kyo] object NetConfigTranslation:

    def toNetTlsConfig(tls: HttpTlsConfig, handshakeTimeout: Duration)(using Frame): Result[NetConfigException, NetTlsConfig] =
        NetTlsConfig.HandshakeTimeout.init(handshakeTimeout).map { deadline =>
            NetTlsConfig(
                trustAll = tls.trustAll,
                sniHostname = tls.sniHostname,
                certChainPath = tls.certChainPath,
                privateKeyPath = tls.privateKeyPath,
                clientAuth = toNetClientAuth(tls.clientAuth),
                trustStorePath = tls.trustStorePath,
                minVersion = toNetVersion(tls.minVersion),
                maxVersion = toNetVersion(tls.maxVersion),
                handshakeTimeout = deadline
                // caCertPath and hostnameVerification take their NetTlsConfig defaults
                // (Absent / true): HttpTlsConfig has no field for them.
            )
        }

    def toHttpAddress(addr: NetAddress): HttpAddress =
        addr match
            case NetAddress.Tcp(host, port) => HttpAddress.Tcp(host, port)
            case NetAddress.Unix(path)      => HttpAddress.Unix(path)

    /** Translate kyo-http's `HttpTransportConfig` to kyo-net's `NetConfig`, the per-connection shape fields whose names match.
      *
      * Passed to each individual operation on the one shared `NetPlatform.transport`, never used to build a transport: that is what lets
      * callers wanting different shapes share a transport. The settings that are not per-connection travel separately, each where it
      * applies: a connect deadline is the `connectTimeout` parameter of the connect operations, and a TLS handshake deadline is
      * `NetTlsConfig.handshakeTimeout`. `maxHeaderSize` is intentionally NOT mapped: it is an HTTP-parser limit kyo-http enforces itself
      * (server dispatch and client connection), not a byte-transport concern, so `kyo.net.NetConfig` has no such field.
      */
    def toNetConfig(c: HttpTransportConfig)(using Frame): Result[NetConfigException, NetConfig] =
        NetConfig.Size.check("channelCapacity", c.channelCapacity).map { channelCapacity =>
            NetConfig(channelCapacity = channelCapacity, readChunkSize = c.readChunkSize.bytes)
        }

    /** Open a client connection: over the Unix socket at `unixSocket` when present, else TLS to `host:port` when `ssl`, else plaintext. */
    def connect(
        transport: Transport,
        unixSocket: Maybe[String],
        host: String,
        port: Int,
        ssl: Boolean,
        tls: HttpTlsConfig,
        connectTimeout: Duration,
        transportConfig: HttpTransportConfig
    )(using AllowUnsafe, Frame): Fiber.Unsafe[kyo.net.Connection, Abort[NetException]] =
        val opened =
            for
                netConfig <- toNetConfig(transportConfig)
                deadline  <- Transport.ConnectTimeout.init(connectTimeout)
                fiber     <- unixSocket match
                    case Present(path) => Result.succeed(transport.connectUnix(path, deadline, netConfig))
                    case Absent if ssl =>
                        toNetTlsConfig(tls, transportConfig.handshakeTimeout)
                            .map(netTls => transport.connectTls(host, port, netTls, deadline, netConfig))
                    case Absent => Result.succeed(transport.connect(host, port, deadline, netConfig))
            yield fiber
        refusedOr(opened)
    end connect

    /** Bind a listener: on the Unix socket at `unixSocket` when present, else TLS on `host:port` when `tls` is present, else plaintext. */
    def listen(
        transport: Transport,
        unixSocket: Maybe[String],
        host: String,
        port: Int,
        backlog: Int,
        tls: Maybe[HttpTlsConfig],
        transportConfig: HttpTransportConfig
    )(handler: kyo.net.Connection => Unit)(using AllowUnsafe, Frame): Fiber.Unsafe[kyo.net.Listener, Abort[NetException]] =
        val bound =
            toNetConfig(transportConfig).flatMap { netConfig =>
                (unixSocket, tls) match
                    case (Present(path), _)     => Result.succeed(transport.listenUnix(path, backlog, netConfig)(handler))
                    case (Absent, Present(tls)) =>
                        toNetTlsConfig(tls, transportConfig.handshakeTimeout)
                            .map(netTls => transport.listenTls(host, port, backlog, netTls, netConfig)(handler))
                    case _ => Result.succeed(transport.listen(host, port, backlog, netConfig)(handler))
            }
        refusedOr(bound)
    end listen

    private def refusedOr[A](
        checked: Result[NetConfigException, Fiber.Unsafe[A, Abort[NetException]]]
    )(using AllowUnsafe): Fiber.Unsafe[A, Abort[NetException]] =
        checked match
            case Result.Success(fiber)   => fiber
            case Result.Failure(refused) => Fiber.Unsafe.fromResult(Result.fail(refused))
            case Result.Panic(thrown)    => Fiber.Unsafe.fromResult(Result.panic(thrown))

    private def toNetClientAuth(auth: HttpTlsConfig.ClientAuth): NetTlsConfig.ClientAuth =
        auth match
            case HttpTlsConfig.ClientAuth.None     => NetTlsConfig.ClientAuth.None
            case HttpTlsConfig.ClientAuth.Optional => NetTlsConfig.ClientAuth.Optional
            case HttpTlsConfig.ClientAuth.Required => NetTlsConfig.ClientAuth.Required

    private def toNetVersion(version: HttpTlsConfig.Version): NetTlsConfig.Version =
        version match
            case HttpTlsConfig.Version.TLS12 => NetTlsConfig.Version.TLS12
            case HttpTlsConfig.Version.TLS13 => NetTlsConfig.Version.TLS13

end NetConfigTranslation
