package kyo

/** Interactivity ADT: typed cases for the named interaction kinds, plus `Unknown`
  * carrying the raw payload JSON for any unmodeled kind. The public `BlockActions`
  * carries no `responseUrl` field; the framework correlates the incoming
  * `response_url` and uses it when it emits the ack. A `BlockActions` fired from inside
  * a modal carries that modal's `viewId` (for `Slack.viewsUpdate`); one fired from a
  * channel message leaves `viewId` absent.
  */
sealed trait SlackInteraction

object SlackInteraction:

    case class BlockActions(
        user: SlackId.UserId,
        triggerId: SlackId.TriggerId,
        channel: Maybe[SlackId.ChannelId],
        viewId: Maybe[SlackId.ViewId] = Absent,
        actions: Chunk[SlackInteraction.Action]
    ) extends SlackInteraction derives Schema, CanEqual

    case class ViewSubmission(
        user: SlackId.UserId,
        viewId: SlackId.ViewId,
        stateJson: String
    ) extends SlackInteraction derives Schema, CanEqual

    case class ViewClosed(
        user: SlackId.UserId,
        viewId: SlackId.ViewId,
        isCleared: Boolean
    ) extends SlackInteraction derives Schema, CanEqual

    case class Shortcut(
        user: SlackId.UserId,
        triggerId: SlackId.TriggerId,
        callbackId: String
    ) extends SlackInteraction derives Schema, CanEqual

    case class MessageAction(
        user: SlackId.UserId,
        triggerId: SlackId.TriggerId,
        callbackId: String,
        channel: SlackId.ChannelId,
        messageTs: SlackTs
    ) extends SlackInteraction derives Schema, CanEqual

    case class Unknown(`type`: String, payloadJson: String) extends SlackInteraction derives CanEqual

    case class Action(
        actionId: SlackId.ActionId,
        blockId: SlackId.BlockId,
        value: Maybe[String] = Absent
    ) derives Schema, CanEqual

end SlackInteraction
