package kyo.internal.transport

import kyo.*
import kyo.net.NetAddress
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.TransportConfig

/** Translation seam between kyo-http's public config/address vocabulary and kyo-net's internal transport types.
  *
  * Both translators are pure total functions. They are the only place a `kyo.net.*` config/address type appears in kyo-http; the
  * `kyo.net.NetTlsConfig` / `kyo.net.NetAddress` types never escape into a `kyo.Http*` public signature. `toNetTlsConfig` copies the 8
  * fields shared with `HttpTlsConfig` by name and leaves the 2 kyo-net-only fields (`caCertPath`, `hostnameVerification`) at their
  * defaults, since `HttpTlsConfig` has no field for them. `toHttpAddress` maps the structurally
  * identical address enum case-for-case so `HttpServer.address` keeps returning `HttpAddress`.
  */
private[kyo] object NetConfigTranslation:

    def toNetTlsConfig(tls: HttpTlsConfig): NetTlsConfig =
        NetTlsConfig(
            trustAll = tls.trustAll,
            sniHostname = tls.sniHostname,
            certChainPath = tls.certChainPath,
            privateKeyPath = tls.privateKeyPath,
            clientAuth = toNetClientAuth(tls.clientAuth),
            trustStorePath = tls.trustStorePath,
            minVersion = toNetVersion(tls.minVersion),
            maxVersion = toNetVersion(tls.maxVersion)
            // caCertPath and hostnameVerification take their NetTlsConfig defaults
            // (Absent / true): HttpTlsConfig has no field for them.
        )

    def toHttpAddress(addr: NetAddress): HttpAddress =
        addr match
            case NetAddress.Tcp(host, port) => HttpAddress.Tcp(host, port)
            case NetAddress.Unix(path)      => HttpAddress.Unix(path)

    /** Translate kyo-http's `HttpTransportConfig` to kyo-net's `TransportConfig` (the five byte-transport fields; the names match). Used to
      * build each client's and server's own transport via `NetPlatform.transport`, so fields like `connectTimeout` (the client TCP connect
      * deadline), `handshakeTimeout` (the slowloris-handshake DoS guard), and `channelCapacity`/`readChunkSize` take effect. `maxHeaderSize`
      * is intentionally NOT mapped: it is an HTTP-parser limit that kyo-http enforces itself (server dispatch and client connection), not a
      * byte-transport concern, so kyo.net.TransportConfig has no such field.
      */
    def toNetTransportConfig(c: HttpTransportConfig): TransportConfig =
        TransportConfig(
            channelCapacity = c.channelCapacity,
            readChunkSize = c.readChunkSize,
            ioPoolSize = c.ioPoolSize,
            connectTimeout = c.connectTimeout,
            handshakeTimeout = c.handshakeTimeout
        )

    /** Wraps transport.connect with TLS config translation. Keeps the kyo.net.NetTlsConfig reference inside internal/. */
    def connectTls(
        transport: kyo.net.Transport,
        host: String,
        port: Int,
        tls: HttpTlsConfig
    )(using AllowUnsafe, Frame): Fiber.Unsafe[kyo.net.Connection, Abort[NetException]] =
        transport.connect(host, port, toNetTlsConfig(tls))

    /** Wraps transport.listen with TLS config translation. Keeps the kyo.net.NetTlsConfig reference inside internal/. */
    def listenTls(
        transport: kyo.net.Transport,
        host: String,
        port: Int,
        backlog: Int,
        tls: HttpTlsConfig
    )(handler: kyo.net.Connection => Unit)(using AllowUnsafe, Frame): Fiber.Unsafe[kyo.net.Listener, Abort[NetException]] =
        transport.listen(host, port, backlog, toNetTlsConfig(tls))(handler)

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
