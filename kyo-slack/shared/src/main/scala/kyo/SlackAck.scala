package kyo

/** The handler RETURN type that drives structural acking: the handler returns one
  * `SlackAck`, and the framework emits exactly one wire ack per envelope from the
  * returned value. There is no public ack method, so forgetting and double-acking
  * are unrepresentable.
  *   - `Ack`: the bare ack.
  *   - `ViewResponse(action)`: a view_submission `response_action` carried inline.
  *   - `CommandResponse(visibility, text, blocks)`: a slash-command immediate response carried inline.
  *
  * Each is one frame on the socket. A payload rides the ack only when the envelope
  * accepts one: an envelope whose `acceptsResponsePayload` is `Present(false)` gets the
  * bare ack, and the payload not sent is logged at warn. An answer through an
  * interaction's `response_url` is not an ack: the handler calls one of the
  * `response_url` operations on [[kyo.Slack]], forked when it can outlast the deadline.
  */

sealed trait SlackAck derives CanEqual

object SlackAck:

    case object Ack extends SlackAck

    final case class ViewResponse(action: SlackAck.ViewAction) extends SlackAck derives CanEqual

    /** The immediate answer to a slash command, Slack's `{"response_type","text","blocks"}`. It names no channel: Slack posts
      * it where the command was typed, visible to the person who typed it or to everyone there.
      */
    final case class CommandResponse(
        visibility: CommandResponse.Visibility,
        text: String,
        blocks: Chunk[SlackBlock] = Chunk.empty
    ) extends SlackAck derives CanEqual

    object CommandResponse:
        /** Who sees the answer: `Ephemeral` only the person who ran the command, `InChannel` everyone in the conversation. */
        enum Visibility derives CanEqual:
            case Ephemeral, InChannel
    end CommandResponse

    /** The four view_submission `response_action` values: `errors`, `update`,
      * `push`, and `clear`. No other value is valid on the Slack wire.
      */
    enum ViewAction derives CanEqual:
        case Errors(byBlock: Map[SlackId.BlockId, String])
        case Update(view: SlackView)
        case Push(view: SlackView)
        case Clear
    end ViewAction

end SlackAck
