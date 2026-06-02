package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** MCP server demonstrating the sampling reverse-direction protocol.
  *
  * One tool (`summarize_text`) that turns the body of a long string into a prompt and
  * delegates generation to the connected client via [[McpServer.requestSampling]]. The
  * returned [[McpServer.SamplingResponse]] is forwarded back as the tool's text content.
  *
  * No command-line arguments.
  */
object Summarize extends KyoApp:

    case class SummarizeText(text: String, maxTokens: Maybe[Int] = Present(256)) derives Schema, CanEqual

    case class SamplingRejected(reason: String) derives Schema, CanEqual

    run {
        val tool =
            McpHandler.tool[SummarizeText](
                name = "summarize_text",
                description = "Asks the connected client to sample a short summary of the provided text."
            ) { req =>
                val request = McpServer.SamplingRequest(
                    messages = Chunk(McpServer.SamplingRequest.Message(
                        role = McpContent.Role.User,
                        content = McpServer.SamplingContent.Text(
                            text = s"Summarize in 1 sentence: ${req.text}"
                        )
                    )),
                    maxTokens = req.maxTokens.getOrElse(256).max(16).min(2048)
                )
                Mcp.server.map(_.requestSampling(request)).map { resp =>
                    resp.content match
                        case t: McpContent.Text =>
                            McpContent.text(s"[model=${resp.model}; stop=${resp.stopReason}] ${t.text}")
                        case other =>
                            McpContent.text(s"[model=${resp.model}; stop=${resp.stopReason}] (non-text content: $other)")
                }.handle(Abort.recover[McpException] { ex =>
                    Abort.fail(SamplingRejected(reason = ex.getMessage))
                })
            }.error[SamplingRejected](code = -32050, message = "sampling-rejected")

        JsonRpcTransport.stdio().map { t =>
            McpServer.initWith(t, tool) { _ =>
                Async.never
            }
        }
    }
end Summarize
