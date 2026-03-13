package kyo

import kyo.*

/** Server binding configuration controlling port, host, content limits, and optional features.
  *
  * @see
  *   [[kyo.HttpServer.init]] Uses this config to bind a server
  * @see
  *   [[kyo.HttpFilter.server.cors]] Per-route CORS filter (alternative to server-wide)
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
    cors: Maybe[HttpServerConfig.Cors]
) derives CanEqual:
    def port(p: Int): HttpServerConfig                    = copy(port = p)
    def host(h: String): HttpServerConfig                 = copy(host = h)
    def maxContentLength(v: Int): HttpServerConfig        = copy(maxContentLength = v)
    def backlog(v: Int): HttpServerConfig                 = copy(backlog = v)
    def keepAlive(v: Boolean): HttpServerConfig           = copy(keepAlive = v)
    def tcpFastOpen(v: Boolean): HttpServerConfig         = copy(tcpFastOpen = v)
    def flushConsolidationLimit(v: Int): HttpServerConfig = copy(flushConsolidationLimit = v)
    def strictCookieParsing(v: Boolean): HttpServerConfig = copy(strictCookieParsing = v)
    def cors(c: HttpServerConfig.Cors): HttpServerConfig  = copy(cors = Present(c))
    def openApi(
        path: String = "/openapi.json",
        title: String = "API",
        version: String = "1.0.0",
        description: Option[String] = None
    ): HttpServerConfig =
        copy(openApi = Present(HttpServerConfig.OpenApiEndpoint(path, title, version, description)))
end HttpServerConfig

object HttpServerConfig:
    val default: HttpServerConfig = HttpServerConfig(
        port = 0,
        host = "0.0.0.0",
        maxContentLength = 65536,
        backlog = 128,
        keepAlive = true,
        tcpFastOpen = true,
        flushConsolidationLimit = 256,
        strictCookieParsing = false,
        openApi = Absent,
        cors = Absent
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
