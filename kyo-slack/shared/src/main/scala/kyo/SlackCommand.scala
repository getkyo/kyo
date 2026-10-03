package kyo

/** A slash-command payload: the command name, the typed text, the originating
  * channel/user, the trigger id (for opening a modal in response), and the
  * `response_url` for a later answer through the `response_url` operations on [[kyo.Slack]],
  * `Absent` when Slack sent none. Referenced
  * standalone in user pattern matches, so top-level.
  *
  * It has no `Schema`: kyo-slack decodes Slack's own frames, and the command holds a
  * [[kyo.SlackResponseUrl]], a credential that must not be serialized in clear. The companion's
  * given makes `summon[Schema[SlackCommand]]` a compile error saying so.
  */
final case class SlackCommand(
    command: String,
    text: String,
    channel: SlackId.ChannelId,
    user: SlackId.UserId,
    triggerId: SlackId.TriggerId,
    responseUrl: Maybe[SlackResponseUrl]
) derives CanEqual

object SlackCommand:
    inline given noSchema: Schema[SlackCommand] = compiletime.error(
        "SlackCommand has no Schema: kyo-slack decodes Slack's frames itself; build outbound values with the module's own types"
    )
end SlackCommand
