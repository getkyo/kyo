package kyo

import kyo.*

/** Configuration for an [[kyo.HttpServer]], controlling port, host, content limits, TLS, and optional features.
  *
  * Start from `HttpServerConfig.default` and override only the fields you need using the fluent builder methods (`port`, `host`, `tls`,
  * etc.). The default configuration binds to port 0 (OS-assigned) on localhost (`127.0.0.1`).
  *
  * @param port
  *   TCP port to bind on. Defaults to 0 (OS-assigned). Use 0 in tests to avoid port conflicts; the actual port is available via
  *   `HttpServer.Binding.port` after startup.
  * @param host
  *   Network interface to bind on. Defaults to `127.0.0.1` (localhost only). Use `0.0.0.0` to listen on all interfaces.
  * @param maxContentLength
  *   Maximum buffered request body size in bytes. Defaults to 65536 (64KB). Requests exceeding this limit receive a 413 response. Streaming
  *   request bodies are not subject to this limit.
  * @param backlog
  *   TCP listen backlog — maximum number of pending connections queued by the OS before new connections are refused. Defaults to 128.
  * @param keepAlive
  *   Whether to enable HTTP/1.1 keep-alive (connection reuse). Defaults to true. When false, the server closes the connection after each
  *   response.
  * @param tcpFastOpen
  *   Whether to enable TCP Fast Open (TFO) for reduced connection latency. Defaults to true. Has no effect on platforms that don't support
  *   TFO.
  * @param flushConsolidationLimit
  *   Number of write operations to buffer before flushing to the socket. Higher values reduce syscall overhead at the cost of latency.
  *   Defaults to 256.
  * @param strictCookieParsing
  *   Whether to enforce strict RFC 6265 cookie parsing. Defaults to false (lenient). When true, malformed cookies cause a 400 response.
  * @param openApi
  *   When set, auto-generates and serves an OpenAPI 3.x JSON spec at the configured path. Absent by default.
  * @param cors
  *   When set, applies a CORS policy globally to all handlers, adding the appropriate `Access-Control-*` headers and handling preflight
  *   OPTIONS requests. Absent by default.
  * @param tls
  *   When set, enables TLS termination with the given certificate chain and private key. Absent by default (plain HTTP).
  * @param unixSocket
  *   When set, binds to a Unix domain socket at the given path instead of TCP. The `port` field is ignored. Absent by default.
  * @param transportConfig
  *   Low-level I/O tuning: read buffer size, channel capacity, I/O pool size. See [[HttpTransportConfig]].
  * @param idleTimeout
  *   Duration after which idle keep-alive connections are closed. Defaults to 60 seconds. Set to `Duration.Infinity` to disable.
  *
  * @see
  *   [[kyo.HttpServer.init]] Uses this config to bind a server
  * @see
  *   [[kyo.HttpTlsConfig]] TLS certificate and client-auth options
  * @see
  *   [[kyo.HttpTransportConfig]] Low-level I/O buffer and event-loop tuning
  */
case class HttpServerConfig(
    port: Int,
    host: String,
    maxContentLength: Int,
    backlog: Int,
    keepAlive: Boolean,
    tcpFastOpen: Boolean,
    flushConsolidationLimit: Int,
    strictCookieParsing: Boolean,
    openApi: Maybe[HttpServerConfig.OpenApiEndpoint],
    cors: Maybe[HttpServerConfig.Cors],
    tls: Maybe[HttpTlsConfig],
    unixSocket: Maybe[String] = Absent,
    transportConfig: HttpTransportConfig = HttpTransportConfig.default,
    idleTimeout: Duration = 60.seconds
) derives CanEqual:
    def port(p: Int): HttpServerConfig                            = copy(port = p)
    def host(h: String): HttpServerConfig                         = copy(host = h)
    def maxContentLength(v: Int): HttpServerConfig                = copy(maxContentLength = v)
    def backlog(v: Int): HttpServerConfig                         = copy(backlog = v)
    def keepAlive(v: Boolean): HttpServerConfig                   = copy(keepAlive = v)
    def tcpFastOpen(v: Boolean): HttpServerConfig                 = copy(tcpFastOpen = v)
    def flushConsolidationLimit(v: Int): HttpServerConfig         = copy(flushConsolidationLimit = v)
    def strictCookieParsing(v: Boolean): HttpServerConfig         = copy(strictCookieParsing = v)
    def cors(c: HttpServerConfig.Cors): HttpServerConfig          = copy(cors = Present(c))
    def tls(config: HttpTlsConfig): HttpServerConfig              = copy(tls = Present(config))
    def unixSocket(path: String): HttpServerConfig                = copy(unixSocket = Present(path))
    def transportConfig(v: HttpTransportConfig): HttpServerConfig = copy(transportConfig = v)
    def idleTimeout(v: Duration): HttpServerConfig                = copy(idleTimeout = v)
    def openApi(
        path: String = "/openapi.json",
        title: String = "API",
        version: String = "1.0.0",
        description: Option[String] = None
    ): HttpServerConfig =
        copy(openApi = Present(HttpServerConfig.OpenApiEndpoint(path, title, version, description)))
end HttpServerConfig

object HttpServerConfig:

    val default: HttpServerConfig =
        HttpServerConfig(
            port = 0,
            host = "127.0.0.1",
            maxContentLength = 65536,
            backlog = 128,
            keepAlive = true,
            tcpFastOpen = true,
            flushConsolidationLimit = 256,
            strictCookieParsing = false,
            openApi = Absent,
            cors = Absent,
            tls = Absent,
            unixSocket = Absent,
            transportConfig = HttpTransportConfig.default,
            idleTimeout = 60.seconds
        )

    case class OpenApiEndpoint(
        path: String = "/openapi.json",
        title: String = "API",
        version: String = "1.0.0",
        description: Option[String] = None
    ) derives CanEqual

    case class Cors(
        allowOrigin: String = "*",
        allowHeaders: Seq[String] = Seq("Content-Type", "Authorization"),
        exposeHeaders: Seq[String] = Seq.empty,
        maxAge: Int = 86400,
        allowCredentials: Boolean = false
    ) derives CanEqual

    object Cors:
        val allowAll: Cors = Cors()
    end Cors

end HttpServerConfig
