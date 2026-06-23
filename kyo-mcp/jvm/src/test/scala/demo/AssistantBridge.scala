package demo

import kyo.*

/** AssistantBridge: a reverse-direction MCP server (server-to-client requests).
  *
  * Most MCP servers only answer the host. This one calls back into the host mid-handler, exercising
  * the three reverse-direction requests:
  *
  *   - `digest` asks the host for its workspace roots (`Mcp.requestRoots`), then asks the host's own
  *     model to write a summary (`Mcp.requestSampling`), and returns it.
  *   - `cleanup` asks the host (or its user) to confirm before acting (`Mcp.requestElicitation`),
  *     branching on accept / decline / cancel.
  *
  * A reverse call the host does not support surfaces as a typed failure on the tool's row rather
  * than a hang, so the tool degrades cleanly.
  *
  * Run as a stdio MCP server: `java -cp <kyo-mcpJVM test classpath> demo.AssistantBridge`.
  */
object AssistantBridge extends KyoApp:

    case class DigestIn(focus: String = "what this project does") derives Schema, CanEqual
    case class DigestOut(roots: Chunk[String], summary: String) derives Schema, CanEqual
    case class CleanupIn(target: String) derives Schema, CanEqual
    case class CleanupOut(action: String, detail: String) derives Schema, CanEqual
    case class Confirm(confirmed: Boolean) derives Schema, CanEqual

    private def contentText(c: McpContent): String =
        c match
            case McpContent.Text(t, _) => t
            case other                 => s"(non-text content: $other)"

    run {
        val digest =
            McpHandler.tool[DigestIn]("digest", "Summarize the workspace using the host's roots and model") { in =>
                for
                    roots <- Mcp.requestRoots
                    names = roots.map(_.uri.toString)
                    resp <- Mcp.requestSampling(
                        McpServer.SamplingRequest.user(
                            s"In two sentences, summarize ${in.focus}. Workspace roots: ${names.mkString(", ")}.",
                            maxTokens = 256
                        )
                    )
                yield DigestOut(names, contentText(resp.content))
            }

        val cleanup =
            McpHandler.tool[CleanupIn]("cleanup", "Ask the host to confirm before cleaning a target") { in =>
                Mcp.requestElicitation(
                    McpServer.ElicitationRequest(
                        s"Confirm cleanup of '${in.target}'? This cannot be undone.",
                        Json.jsonSchema[Confirm]
                    )
                ).map { resp =>
                    resp.action match
                        case McpServer.ElicitationResponse.Action.Accept  => CleanupOut("accepted", s"would clean '${in.target}'")
                        case McpServer.ElicitationResponse.Action.Decline => CleanupOut("declined", "left untouched")
                        case McpServer.ElicitationResponse.Action.Cancel  => CleanupOut("cancelled", "left untouched")
                }
            }

        JsonRpcTransport.stdio().map(t => McpServer.initWith(t, digest, cleanup)(_ => Async.never))
    }
end AssistantBridge
