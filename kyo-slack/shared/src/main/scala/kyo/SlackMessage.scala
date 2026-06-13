package kyo

/** The common outbound message value for the `chat.*` Web API methods and a
  * block_actions `response_url` update: the channel, the text, optional Block Kit
  * blocks as raw JSON (Block Kit is not typed; the raw JSON escape), and an optional
  * thread timestamp. The blocks are sent to Slack as a JSON array.
  */
case class SlackMessage(
    channel: SlackId.ChannelId,
    text: String,
    blocksJson: Maybe[String] = Absent,
    threadTs: Maybe[SlackTs] = Absent
) derives Schema, CanEqual
