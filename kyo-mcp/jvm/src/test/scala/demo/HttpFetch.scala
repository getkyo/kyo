package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** MCP server exposing HTTP GET as a tool, backed by [[kyo.HttpClient]].
  *
  * One tool (`http_get`) and one resource template (`http://{url}`) over stdio. Demonstrates:
  *
  *   - Cross-module integration: kyo-mcp + kyo-http in one binary.
  *   - `HttpClient.withConfig` to scope a per-request timeout and redirect policy.
  *   - Typed [[kyo.HttpException]] mapping to a user-visible `HttpError` via `.error[E2]`.
  *   - Truncation of large response bodies to keep the wire response bounded.
  *
  * No command-line arguments.
  */
object HttpFetch extends KyoApp:

    case class HttpGet(url: String, maxBytes: Maybe[Int] = Absent) derives Schema, CanEqual

    case class HttpError(reason: String, url: String) derives Schema, CanEqual

    // 1 MiB default cap on the response body the tool returns; clients can shrink via `maxBytes`.
    private val DefaultMaxBytes: Int = 1 * 1024 * 1024

    run {
        val getTool =
            McpHandler.tool[HttpGet](
                name = "http_get",
                description = "GET the given URL and return the response body as text (UTF-8). " +
                    "Body is truncated to `maxBytes` (default 1 MiB). Follows redirects; 10s timeout."
            ) { req =>
                val cap = req.maxBytes.getOrElse(DefaultMaxBytes).max(0).min(DefaultMaxBytes * 4)
                HttpClient
                    .withConfig(_.timeout(10.seconds).followRedirects(true)) {
                        HttpClient.getText(req.url)
                    }
                    .map { body =>
                        val out = if body.length > cap then body.take(cap) + s"\n…[truncated at $cap bytes]" else body
                        McpContent.text(out)
                    }
                    .handle(Abort.recover[HttpException] { ex =>
                        Abort.fail(HttpError(reason = ex.getMessage, url = req.url))
                    })
            }.error[HttpError](code = -32020, message = "http-error")

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
