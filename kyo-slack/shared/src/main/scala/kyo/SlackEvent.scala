package kyo

import kyo.schema.rename

/** Events API event ADT: typed cases for the named Slack events, plus `Unknown`
  * carrying the raw inner event as a [[kyo.SlackRawJson]] for an event type kyo-slack does
  * not model or an event that does not decode, so no event is lost.
  *
  * Each typed case's `Schema` is Slack's event object for its type, the `type` key aside, which
  * the root names. `SlackEvent` itself has no `Schema` yet: a given makes summoning it, or a case
  * without one of its own, a compile error, since kyo-schema would otherwise derive one that is not
  * Slack's JSON.
  */
sealed trait SlackEvent derives CanEqual

object SlackEvent:

    // Bounded rather than on `SlackEvent` alone, so the refusal also answers a summon of a case with no `Schema` of its own.
    inline given [U <: SlackEvent]: Schema[U] = compiletime.error(
        "SlackEvent has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
    )

    final case class Message(
        channel: SlackId.ChannelId,
        user: SlackId.UserId,
        text: String,
        ts: SlackTs,
        @rename("thread_ts") threadTs: Maybe[SlackTs] = Absent
    ) extends SlackEvent derives CanEqual, Schema

    final case class AppMention(
        channel: SlackId.ChannelId,
        user: SlackId.UserId,
        text: String,
        ts: SlackTs
    ) extends SlackEvent derives CanEqual, Schema

    /** A reaction added to the message `item` names. */
    final case class ReactionAdded(
        user: SlackId.UserId,
        reaction: String,
        item: ReactionAdded.Item
    ) extends SlackEvent derives CanEqual, Schema

    object ReactionAdded:
        /** The message a reaction was added to: its channel and its timestamp. */
        final case class Item(channel: SlackId.ChannelId, ts: SlackTs) derives CanEqual, Schema

    final case class AppHomeOpened(
        user: SlackId.UserId,
        channel: SlackId.ChannelId,
        tab: Maybe[String] = Absent
    ) extends SlackEvent derives CanEqual, Schema

    final case class MemberJoinedChannel(
        user: SlackId.UserId,
        channel: SlackId.ChannelId,
        inviter: Maybe[SlackId.UserId] = Absent
    ) extends SlackEvent derives CanEqual, Schema

    final case class Unknown(`type`: String, payload: SlackRawJson) extends SlackEvent derives CanEqual

end SlackEvent
