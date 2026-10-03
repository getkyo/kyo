package kyo.internal.transport

import kyo.*
import kyo.net.NetAddress
import kyo.net.NetConfig
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Transport

/** Translation seam between kyo-http's public config/address vocabulary and kyo-net's internal transport types.
  *
  * It is the only place kyo-http builds a `kyo.net` config or reads a `kyo.net` address: `kyo.net.NetTlsConfig`, `kyo.net.NetConfig` and
  * `kyo.net.NetAddress` never escape into a `kyo.Http*` public signature. The checked values themselves are shared rather than translated:
  * a `kyo.Http*` config holds kyo-net's `NetConfig.Size` and deadline types, the one definition of each. `toNetTlsConfig` copies the 8
  * fields shared with `HttpTlsConfig` by name and leaves the 2
  * kyo-net-only fields (`caCertPath`, `hostnameVerification`) at their defaults, since `HttpTlsConfig` has no field for them. `toHttpAddress`
  * maps the structurally identical address enum case-for-case so `HttpServer.address` keeps returning `HttpAddress`.
  *
  * The checked values a connection needs (the channel capacity, the connect and handshake deadlines) are kyo-net's own types in kyo-http's
  * config, so every translator is total: a value kyo-net would refuse is refused where the config is built.
  */
private[kyo] object NetConfigTranslation:

    def toNetTlsConfig(tls: HttpTlsConfig, handshakeTimeout: NetTlsConfig.HandshakeTimeout): NetTlsConfig =
        NetTlsConfig(
            trustAll = tls.trustAll,
            sniHostname = tls.sniHostname,
            certChainPath = tls.certChainPath,
            privateKeyPath = tls.privateKeyPath,
            clientAuth = toNetClientAuth(tls.clientAuth),
            trustStorePath = tls.trustStorePath,
            minVersion = toNetVersion(tls.minVersion),
            maxVersion = toNetVersion(tls.maxVersion),
            handshakeTimeout = handshakeTimeout
            // caCertPath and hostnameVerification take their NetTlsConfig defaults
            // (Absent / true): HttpTlsConfig has no field for them.
        )

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
    def toNetConfig(c: HttpTransportConfig): NetConfig =
        NetConfig(channelCapacity = c.channelCapacity, readChunkSize = c.readChunkSize)

    /** Open a client connection: over the Unix socket at `unixSocket` when present, else TLS to `host:port` when `ssl`, else plaintext. */
    def connect(
        transport: Transport,
        unixSocket: Maybe[String],
        host: String,
        port: Int,
        ssl: Boolean,
        tls: HttpTlsConfig,
        connectTimeout: Transport.ConnectTimeout,
        transportConfig: HttpTransportConfig
    )(using AllowUnsafe, Frame): Fiber.Unsafe[kyo.net.Connection, Abort[NetException]] =
        val netConfig = toNetConfig(transportConfig)
        unixSocket match
            case Present(path) => transport.connectUnix(path, connectTimeout, netConfig)
            case Absent if ssl =>
                transport.connectTls(host, port, toNetTlsConfig(tls, transportConfig.handshakeTimeout), connectTimeout, netConfig)
            case Absent => transport.connect(host, port, connectTimeout, netConfig)
        end match
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
        val netConfig = toNetConfig(transportConfig)
        (unixSocket, tls) match
            case (Present(path), _)     => transport.listenUnix(path, backlog, netConfig)(handler)
            case (Absent, Present(tls)) =>
                transport.listenTls(host, port, backlog, toNetTlsConfig(tls, transportConfig.handshakeTimeout), netConfig)(handler)
            case _ => transport.listen(host, port, backlog, netConfig)(handler)
        end match
    end listen

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
