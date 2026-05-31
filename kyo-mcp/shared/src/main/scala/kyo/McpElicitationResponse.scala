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
        // Phase 1 stub; Phase 3 fills with stringSchema.transform
        // Wire strings: "accept" | "decline" | "cancel"
        given Schema[Action] = new Schema[Action](Seq.empty):
            import scala.annotation.publicInBinary
            @publicInBinary private[kyo] def serializeWrite(v: Action, w: kyo.Codec.Writer): Unit =
                throw new NotImplementedError("McpElicitationResponse.Action.Schema stub: body filled in Phase 3")
            @publicInBinary private[kyo] def serializeRead(r: kyo.Codec.Reader): Action =
                throw new NotImplementedError("McpElicitationResponse.Action.Schema stub: body filled in Phase 3")
            @publicInBinary private[kyo] def getter(v: Action): Maybe[Any]        = Maybe(v)
            @publicInBinary private[kyo] def setter(v: Action, next: Any): Action = v
    end Action

end McpElicitationResponse
