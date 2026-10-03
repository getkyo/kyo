package kyo

import kyo.schema.rename

/** Interactivity ADT: typed cases for the named interaction kinds, plus `Unknown`
  * carrying the raw payload as a [[kyo.SlackRawJson]] for an unmodeled kind or a payload that
  * does not decode. A `BlockActions` fired from inside a modal carries that modal's `viewId`
  * (for `Slack.viewsUpdate`); one fired from a channel message leaves `viewId` absent.
  *
  * `BlockActions` and `MessageAction` carry the interaction's `response_url` as a
  * [[kyo.SlackResponseUrl]], `Absent` when Slack sent none; the `response_url` operations on
  * [[kyo.Slack]] answer through it.
  *
  * Every typed case holds the person it came from as Slack's `user` object, a [[kyo.SlackInteraction.User]].
  * `ViewClosed` and `Shortcut` have a `Schema` that is Slack's payload for their type, the `type`
  * key aside, which the root names. `SlackInteraction` itself and its other cases have none yet: a
  * given makes summoning one a compile error, since kyo-schema would otherwise derive one that is
  * not Slack's JSON.
  */
sealed trait SlackInteraction derives CanEqual

object SlackInteraction:

    // Bounded rather than on `SlackInteraction` alone, so the refusal also answers a summon of a case with no `Schema` of its own.
    inline given [U <: SlackInteraction]: Schema[U] = compiletime.error(
        "SlackInteraction has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
    )

    /** The person an interaction came from: Slack's `user` object, of which the module models the `id`. */
    final case class User(id: SlackId.UserId) derives CanEqual, Schema

    /** A click on an interactive block. `messageTs` is the timestamp of the message the block was
      * in, from Slack's `message.ts`, `Absent` when the block was not in a message (a modal, the App
      * Home). With `channel`, it addresses that message for `Slack.chatUpdate`.
      */
    final case class BlockActions(
        user: User,
        triggerId: SlackId.TriggerId,
        channel: Maybe[SlackId.ChannelId],
        viewId: Maybe[SlackId.ViewId] = Absent,
        actions: Chunk[SlackInteraction.Action],
        messageTs: Maybe[SlackTs] = Absent,
        responseUrl: Maybe[SlackResponseUrl] = Absent
    ) extends SlackInteraction derives CanEqual

    /** A modal's submission, with the submitted `view`. */
    final case class ViewSubmission(
        user: User,
        view: ViewSubmission.View
    ) extends SlackInteraction derives CanEqual

    object ViewSubmission:
        /** The submitted view: its `id` and its `state`, the form's values as Slack sent them, or `Absent` when the payload has
          * none. The state holds what the person typed, so it is a `SlackRawJson`: a printed interaction shows its length, and
          * `value` reads the text.
          */
        final case class View(id: SlackId.ViewId, state: Maybe[SlackRawJson] = Absent) derives CanEqual
    end ViewSubmission

    /** A modal closed without submitting, when the view set `notify_on_close`. */
    final case class ViewClosed(
        user: User,
        view: ViewClosed.View,
        @rename("is_cleared") isCleared: Boolean
    ) extends SlackInteraction derives CanEqual, Schema:
        def viewId: SlackId.ViewId = view.id
    end ViewClosed

    object ViewClosed:
        /** The closed view: Slack's `view` object, of which the module models the `id`. */
        final case class View(id: SlackId.ViewId) derives CanEqual, Schema

    final case class Shortcut(
        user: User,
        @rename("trigger_id") triggerId: SlackId.TriggerId,
        @rename("callback_id") callbackId: String
    ) extends SlackInteraction derives CanEqual, Schema

    final case class MessageAction(
        user: User,
        triggerId: SlackId.TriggerId,
        callbackId: String,
        channel: SlackId.ChannelId,
        messageTs: SlackTs,
        responseUrl: Maybe[SlackResponseUrl] = Absent
    ) extends SlackInteraction derives CanEqual

    final case class Unknown(`type`: String, payload: SlackRawJson) extends SlackInteraction derives CanEqual

    /** One action of a `BlockActions`; its `Schema` is Slack's action object. */
    final case class Action(
        @rename("action_id") actionId: SlackId.ActionId,
        @rename("block_id") blockId: SlackId.BlockId,
        value: Maybe[String] = Absent
    ) derives CanEqual, Schema

end SlackInteraction
