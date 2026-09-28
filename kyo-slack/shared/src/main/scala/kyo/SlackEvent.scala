package kyo

/** Events API event ADT: typed cases for the named Slack events, plus `Unknown`
  * carrying the raw inner event as a [[kyo.SlackRawJson]] for an event type kyo-slack does
  * not model or an event that does not decode, so no event is lost.
  *
  * No event type has a `Schema`. kyo-slack decodes Slack's own frames through its internal wire
  * types, and a `Schema` on these camelCase types would read and write a shape that is not Slack's.
  * kyo-schema derives a `Schema` for any case class on demand, so a given makes `summon[Schema[X]]`
  * a compile error saying so.
  */
sealed trait SlackEvent derives CanEqual

object SlackEvent:

    // Bounded rather than on `SlackEvent` alone, so the refusal also answers a summon of one case, such as `Message`.
    inline given [U <: SlackEvent]: Schema[U] = compiletime.error(
        "SlackEvent has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
    )

    final case class Message(
        channel: SlackId.ChannelId,
        user: SlackId.UserId,
        text: String,
        ts: SlackTs,
        threadTs: Maybe[SlackTs] = Absent
    ) extends SlackEvent derives CanEqual

    final case class AppMention(
        channel: SlackId.ChannelId,
        user: SlackId.UserId,
        text: String,
        ts: SlackTs
    ) extends SlackEvent derives CanEqual

    final case class ReactionAdded(
        user: SlackId.UserId,
        reaction: String,
        itemChannel: SlackId.ChannelId,
        itemTs: SlackTs
    ) extends SlackEvent derives CanEqual

    final case class AppHomeOpened(
        user: SlackId.UserId,
        channel: SlackId.ChannelId,
        tab: Maybe[String] = Absent
    ) extends SlackEvent derives CanEqual

    final case class MemberJoinedChannel(
        user: SlackId.UserId,
        channel: SlackId.ChannelId,
        inviter: Maybe[SlackId.UserId] = Absent
    ) extends SlackEvent derives CanEqual

    final case class Unknown(`type`: String, payload: SlackRawJson) extends SlackEvent derives CanEqual

end SlackEvent
