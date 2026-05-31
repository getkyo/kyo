package kyo

/** Result of the `elicitation/create` reverse-direction request.
  *
  * The `content` field is an INV-021 allowlist pass-through: the MCP spec leaves the
  * elicitation response payload as an open JSON object, so it is surfaced as
  * `Maybe[Structure.Value]`.
  *
  * @param action  whether the user accepted, declined, or cancelled the elicitation
  * @param content the user-supplied content when action is Accept (INV-021 allowlist per §11a)
  */
// flow-allow: Structure carve-out per §11a / INV-021
final case class McpElicitationResponse(
    action: McpElicitationResponse.Action,
    // flow-allow: Structure carve-out per §11a / INV-021
    content: Maybe[Structure.Value] = Absent
) derives Schema, CanEqual

object McpElicitationResponse:

    /** The user's decision regarding the elicitation request. */
    enum Action derives CanEqual:
        case Accept, Decline, Cancel

    object Action:
        // Wire strings: "accept" | "decline" | "cancel" (INV-010).
        // capitalize maps lowercase wire string to Scala case name: "accept" -> "Accept".
        given Schema[Action] = Schema.stringSchema.transform(s => Action.valueOf(s.capitalize))(
            _.toString.toLowerCase
        )
    end Action

end McpElicitationResponse
