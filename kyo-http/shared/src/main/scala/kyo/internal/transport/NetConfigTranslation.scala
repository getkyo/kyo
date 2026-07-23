package kyo.internal.transport

import kyo.*
import kyo.net.NetAddress
import kyo.net.NetConfig
import kyo.net.NetException
import kyo.net.NetTlsConfig

/** Translation seam between kyo-http's public config/address vocabulary and kyo-net's internal transport types.
  *
  * Both translators are pure total functions. They are the only place a `kyo.net.*` config/address type appears in kyo-http; the
  * `kyo.net.NetTlsConfig` / `kyo.net.NetAddress` types never escape into a `kyo.Http*` public signature. `toNetTlsConfig` copies the 8
  * fields shared with `HttpTlsConfig` by name and leaves the 2 kyo-net-only fields (`caCertPath`, `hostnameVerification`) at their
  * defaults, since `HttpTlsConfig` has no field for them. `toHttpAddress` maps the structurally
  * identical address enum case-for-case so `HttpServer.address` keeps returning `HttpAddress`.
  */
private[kyo] object NetConfigTranslation:

    def toNetTlsConfig(tls: HttpTlsConfig, handshakeTimeout: Duration): NetTlsConfig =
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

    /** Wraps transport.connect with TLS config translation. Keeps the kyo.net.NetTlsConfig reference inside internal/. */
    def connectTls(
        transport: kyo.net.Transport,
        host: String,
        port: Int,
        tls: HttpTlsConfig,
        connectTimeout: Duration,
        transportConfig: HttpTransportConfig
    )(using AllowUnsafe, Frame): Fiber.Unsafe[kyo.net.Connection, Abort[NetException]] =
        transport.connectTls(
            host,
            port,
            toNetTlsConfig(tls, transportConfig.handshakeTimeout),
            connectTimeout,
            toNetConfig(transportConfig)
        )

    /** Wraps transport.listen with TLS config translation. Keeps the kyo.net.NetTlsConfig reference inside internal/. */
    def listenTls(
        transport: kyo.net.Transport,
        host: String,
        port: Int,
        backlog: Int,
        tls: HttpTlsConfig,
        transportConfig: HttpTransportConfig
    )(handler: kyo.net.Connection => Unit)(using AllowUnsafe, Frame): Fiber.Unsafe[kyo.net.Listener, Abort[NetException]] =
        transport.listenTls(
            host,
            port,
            backlog,
            toNetTlsConfig(tls, transportConfig.handshakeTimeout),
            toNetConfig(transportConfig)
        )(handler)

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
