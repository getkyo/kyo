package kyo

/** Interactivity ADT: typed cases for the named interaction kinds, plus `Unknown`
  * carrying the raw payload as a [[kyo.SlackRawJson]] for an unmodeled kind or a payload that
  * does not decode. A `BlockActions` fired from inside a modal carries that modal's `viewId`
  * (for `Slack.viewsUpdate`); one fired from a channel message leaves `viewId` absent.
  *
  * `BlockActions` and `MessageAction` carry the interaction's `response_url` as a
  * [[kyo.SlackResponseUrl]], `Absent` when Slack sent none; the `response_url` operations on
  * [[kyo.Slack]] answer through it.
  *
  * No interaction type has a `Schema`: kyo-slack decodes Slack's own frames through its internal
  * wire types, and a given makes `summon[Schema[X]]` a compile error saying so, since kyo-schema
  * would otherwise derive one on demand.
  */
sealed trait SlackInteraction derives CanEqual

object SlackInteraction:

    // Bounded rather than on `SlackInteraction` alone, so the refusal also answers a summon of one case, such as `BlockActions`.
    inline given [U <: SlackInteraction]: Schema[U] = compiletime.error(
        "SlackInteraction has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
    )

    /** A click on an interactive block. `messageTs` is the timestamp of the message the block was
      * in, from Slack's `message.ts`, `Absent` when the block was not in a message (a modal, the App
      * Home). With `channel`, it addresses that message for `Slack.chatUpdate`.
      */
    final case class BlockActions(
        user: SlackId.UserId,
        triggerId: SlackId.TriggerId,
        channel: Maybe[SlackId.ChannelId],
        viewId: Maybe[SlackId.ViewId] = Absent,
        actions: Chunk[SlackInteraction.Action],
        messageTs: Maybe[SlackTs] = Absent,
        responseUrl: Maybe[SlackResponseUrl] = Absent
    ) extends SlackInteraction derives CanEqual

    /** A modal's submission. `stateJson` is the submitted form's `view.state` as Slack sent it, or
      * `Absent` when the payload has none. It holds what the person typed, so it is a `SlackRawJson`:
      * a printed interaction shows its length, and `value` reads the text.
      */
    final case class ViewSubmission(
        user: SlackId.UserId,
        viewId: SlackId.ViewId,
        stateJson: Maybe[SlackRawJson]
    ) extends SlackInteraction derives CanEqual

    final case class ViewClosed(
        user: SlackId.UserId,
        viewId: SlackId.ViewId,
        isCleared: Boolean
    ) extends SlackInteraction derives CanEqual

    final case class Shortcut(
        user: SlackId.UserId,
        triggerId: SlackId.TriggerId,
        callbackId: String
    ) extends SlackInteraction derives CanEqual

    final case class MessageAction(
        user: SlackId.UserId,
        triggerId: SlackId.TriggerId,
        callbackId: String,
        channel: SlackId.ChannelId,
        messageTs: SlackTs,
        responseUrl: Maybe[SlackResponseUrl] = Absent
    ) extends SlackInteraction derives CanEqual

    final case class Unknown(`type`: String, payload: SlackRawJson) extends SlackInteraction derives CanEqual

    final case class Action(
        actionId: SlackId.ActionId,
        blockId: SlackId.BlockId,
        value: Maybe[String] = Absent
    ) derives CanEqual

    object Action:
        inline given noSchema: Schema[Action] = compiletime.error(
            "SlackInteraction.Action has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
        )
    end Action

end SlackInteraction
