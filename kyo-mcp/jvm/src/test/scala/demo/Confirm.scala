package demo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** MCP server demonstrating the elicitation reverse-direction protocol.
  *
  * One tool (`destructive_op`) that, before "doing" anything, calls
  * [[McpServer.requestElicitation]] to ask the user to confirm. The three possible user
  * actions (Accept / Decline / Cancel) each map to a distinct outcome.
  *
  * No command-line arguments.
  */
object Confirm extends KyoApp:

    /** Inputs: the target to operate on; the engine asks the user to confirm before "deleting". */
    case class DestructiveOp(target: String) derives Schema, CanEqual

    /** Response schema the host renders the elicitation form for. A real schema (with at least one
      * property) is required by spec-compliant hosts; `Json.JsonSchema.from[Unit]` would emit an
      * empty-property object, which hosts such as Claude Code reject as
      * `requestedSchema.properties: undefined`.
      */
    case class ConfirmResponse(confirm: Boolean) derives Schema, CanEqual

    case class ConfirmDeclined(reason: String) derives Schema, CanEqual

    case class ConfirmHostError(reason: String) derives Schema, CanEqual

    run {
        val destructiveTool =
            McpHandler.tool[DestructiveOp](
                name = "destructive_op",
                description = "Pretends to delete the given target, but only after the user confirms via elicitation."
            ) { req =>
                val request = McpServer.ElicitationRequest(
                    message = s"Really delete '${req.target}'? Confirm yes / no.",
                    requestedSchema = Json.JsonSchema.from[ConfirmResponse]
                )
                Mcp.server.map(_.requestElicitation(request)).map { resp =>
                    resp.action match
                        case McpServer.ElicitationResponse.Action.Accept =>
                            McpContent.text(s"Deleted '${req.target}' (simulated; content=${resp.content}).")
                        case McpServer.ElicitationResponse.Action.Decline =>
                            Abort.fail(ConfirmDeclined(reason = "user declined"))
                        case McpServer.ElicitationResponse.Action.Cancel =>
                            Abort.fail(ConfirmDeclined(reason = "user cancelled"))
                }.handle(Abort.recover[McpException] { ex =>
                    // Surface the host's verbatim message so debugging via the wire `data` field is
                    // possible. Without this the typed error's `message` field is "elicitation-host-error"
                    // and the underlying reason is lost.
                    Abort.fail(ConfirmHostError(reason = Maybe(ex.getMessage).getOrElse(ex.getClass.getSimpleName)))
                })
            }
                .error[ConfirmDeclined](code = -32040, message = "confirm-declined")
                .error[ConfirmHostError](code = -32041, message = "elicitation-host-error")

        JsonRpcTransport.stdio().map { t =>
            McpServer.initWith(t, destructiveTool) { _ =>
                Async.never
            }
        }
    }
end Confirm
