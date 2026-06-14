package kyo

/** Events API event ADT: typed cases for the named Slack events, plus `Unknown`
  * carrying the raw inner JSON for any event type kyo-slack does not model, so no
  * event is lost. Each typed leaf derives `Schema` for round-trip encoding and
  * decoding; `Unknown` is assembled from the raw inner JSON.
  */
sealed trait SlackEvent

object SlackEvent:

    case class Message(
        channel: SlackId.ChannelId,
        user: SlackId.UserId,
        text: String,
        ts: SlackTs,
        threadTs: Maybe[SlackTs] = Absent
    ) extends SlackEvent derives Schema, CanEqual

    case class AppMention(
        channel: SlackId.ChannelId,
        user: SlackId.UserId,
        text: String,
        ts: SlackTs
    ) extends SlackEvent derives Schema, CanEqual

    case class ReactionAdded(
        user: SlackId.UserId,
        reaction: String,
        itemChannel: SlackId.ChannelId,
        itemTs: SlackTs
    ) extends SlackEvent derives Schema, CanEqual

    case class AppHomeOpened(
        user: SlackId.UserId,
        channel: SlackId.ChannelId,
        tab: String
    ) extends SlackEvent derives Schema, CanEqual

    case class MemberJoinedChannel(
        user: SlackId.UserId,
        channel: SlackId.ChannelId,
        inviter: Maybe[SlackId.UserId] = Absent
    ) extends SlackEvent derives Schema, CanEqual

    case class Unknown(`type`: String, eventJson: String) extends SlackEvent derives CanEqual

end SlackEvent
