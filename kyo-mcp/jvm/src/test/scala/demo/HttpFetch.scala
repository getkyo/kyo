package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** MCP server exposing HTTP GET as a tool, backed by [[kyo.HttpClient]].
  *
  * One tool (`http_get`) and one resource template (`http://{url}`) over stdio. Each
  * upstream failure mode maps to a distinct typed error code:
  *
  *   - `-32020 http-bad-url`     — malformed URL or unsupported scheme (only http/https accepted)
  *   - `-32021 http-dns`         — DNS resolution failure
  *   - `-32022 http-connect`     — connection refused / unreachable
  *   - `-32023 http-timeout`     — request timed out (10s default)
  *   - `-32024 http-status`      — non-2xx HTTP status
  *   - `-32025 http-other`       — generic HttpException not covered above
  *   - `-32026 http-bad-input`   — caller-supplied input violates contract (maxBytes <= 0, etc.)
  */
object HttpFetch extends KyoApp:

    case class HttpGet(url: String, maxBytes: Maybe[Int] = Absent) derives Schema, CanEqual

    case class HttpBadUrl(url: String, reason: String) derives Schema, CanEqual
    case class HttpDnsError(url: String, host: String) derives Schema, CanEqual
    case class HttpConnectError(url: String, target: String) derives Schema, CanEqual
    case class HttpTimeoutError(url: String, after: String) derives Schema, CanEqual
    case class HttpStatusError(url: String, status: Int) derives Schema, CanEqual
    case class HttpOtherError(url: String, reason: String) derives Schema, CanEqual
    case class HttpBadInput(reason: String) derives Schema, CanEqual

    private val DefaultMaxBytes: Int = 1 * 1024 * 1024

    run {
        // Map kyo-http's typed exception hierarchy to the demo's typed error variants so the
        // wire response is differentiated per failure mode (closes QUIRK-E from the QA pass).
        def liftHttpException(ex: HttpException, url: String): HttpBadUrl | HttpDnsError | HttpConnectError |
            HttpTimeoutError | HttpStatusError | HttpOtherError =
            ex match
                case e: HttpUrlParseException       => HttpBadUrl(url, e.detail)
                case e: HttpConnectException        => HttpDnsError(url, e.host)
                case e: HttpConnectTimeoutException => HttpConnectError(url, s"${e.host}:${e.port}")
                case e: HttpTimeoutException        => HttpTimeoutError(url, e.duration.show)
                case e: HttpStatusException         => HttpStatusError(url, e.status.code)
                case e                              => HttpOtherError(url, Maybe(e.getMessage).getOrElse(e.getClass.getSimpleName))

        val getTool =
            McpHandler.tool[HttpGet](
                name = "http_get",
                description = "GET the given URL and return the response body as text (UTF-8). " +
                    "Body is truncated to `maxBytes` (default 1 MiB, must be >= 1). Follows redirects; 10s timeout. " +
                    "Only http:// and https:// schemes are accepted."
            ) { req =>
                val rawCap = req.maxBytes.getOrElse(DefaultMaxBytes)
                if rawCap < 1 then
                    Abort.fail(HttpBadInput(s"maxBytes must be >= 1, got $rawCap"))
                else
                    val cap = rawCap.min(DefaultMaxBytes * 4)
                    HttpClient
                        .withConfig(_.timeout(10.seconds).followRedirects(true)) {
                            HttpClient.getText(req.url)
                        }
                        .map { body =>
                            val out = if body.length > cap then body.take(cap) + s"\n…[truncated at $cap bytes]" else body
                            McpContent.text(out)
                        }
                        .handle(Abort.recover[HttpException] { ex =>
                            Abort.fail(liftHttpException(ex, req.url))
                        })
                end if
            }
                .error[HttpBadUrl](code = -32020, message = "http-bad-url")
                .error[HttpDnsError](code = -32021, message = "http-dns")
                .error[HttpConnectError](code = -32022, message = "http-connect")
                .error[HttpTimeoutError](code = -32023, message = "http-timeout")
                .error[HttpStatusError](code = -32024, message = "http-status")
                .error[HttpOtherError](code = -32025, message = "http-other")
                .error[HttpBadInput](code = -32026, message = "http-bad-input")

        // Resource template: clients can read any URL by URI without a tool call.
        val httpUri = McpResourceUri.Template("http://{url}")
        val httpResource =
            McpHandler.resourceTemplate(
                uriTemplate = httpUri,
                name = "http",
                description = "Read an HTTP resource directly via its URI.",
                mimeType = Present(McpMimeType("text/plain"))
            ) { uri =>
                httpUri.extract(uri) match
                    case Absent =>
                        Chunk.empty[McpHandler.ResourceContents]
                    case Present(bindings) =>
                        val target = "http://" + bindings.getOrElse("url", "")
                        HttpClient
                            .withConfig(_.timeout(10.seconds).followRedirects(true)) {
                                HttpClient.getText(target)
                            }
                            .map { body =>
                                Chunk(McpHandler.ResourceContents.text(
                                    uri = uri,
                                    text = body.take(DefaultMaxBytes),
                                    mimeType = Present(McpMimeType("text/plain"))
                                ))
                            }
                            .handle(Abort.recover[HttpException](_ => Chunk.empty[McpHandler.ResourceContents]))
            }

        JsonRpcTransport.stdio().map { t =>
            McpServer.initWith(t, getTool, httpResource) { _ =>
                Async.never
            }
        }
    }
end HttpFetch
