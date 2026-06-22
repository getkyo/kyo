package kyo

/** The handler RETURN type that drives structural acking: the handler returns one
  * `SlackAck`, and the framework emits exactly one wire ack per envelope from the
  * returned value. There is no public ack method, so forgetting and double-acking
  * are unrepresentable.
  *   - `Ack`: the bare ack.
  *   - `ViewResponse(action)`: a view_submission `response_action` carried inline.
  *   - `CommandResponse(message)`: a slash-command immediate response carried inline.
  *   - `BlockActionsResponse(message)`: a bare ack plus a `response_url` POST (the
  *     block_actions update path; the socket ack itself is bare).
  */

sealed trait SlackAck

object SlackAck:

    case object Ack extends SlackAck

    case class ViewResponse(action: SlackAck.ViewAction) extends SlackAck derives CanEqual

    case class CommandResponse(message: SlackMessage) extends SlackAck derives CanEqual

    case class BlockActionsResponse(message: SlackMessage) extends SlackAck derives CanEqual

    /** The four view_submission `response_action` values: `errors`, `update`,
      * `push`, and `clear`. No other value is valid on the Slack wire.
      */
    enum ViewAction derives CanEqual:
        case Errors(byBlock: Map[String, String])
        case Update(view: SlackView)
        case Push(view: SlackView)
        case Clear
    end ViewAction

end SlackAck
