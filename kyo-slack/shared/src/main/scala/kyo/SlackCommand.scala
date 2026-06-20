package kyo

/** A slash-command payload: the command name, the typed text, the originating
  * channel/user, the trigger id (for opening a modal in response), and the
  * `response_url` for a delayed followup. Referenced standalone in user pattern
  * matches, so top-level.
  */
case class SlackCommand(
    command: String,
    text: String,
    channel: SlackId.ChannelId,
    user: SlackId.UserId,
    triggerId: SlackId.TriggerId,
    responseUrl: String
) derives Schema, CanEqual
