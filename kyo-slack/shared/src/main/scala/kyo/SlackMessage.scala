package kyo

/** The common outbound message value for the `chat.*` Web API methods and a block_actions
  * `response_url` update: the channel, the text, an optional thread timestamp, and the typed
  * Block Kit `blocks` (a `Chunk[SlackBlock]`; empty for a plain text message). The blocks are
  * rendered to a Block Kit JSON array on the wire.
  */
case class SlackMessage(
    channel: SlackId.ChannelId,
    text: String,
    threadTs: Maybe[SlackTs] = Absent,
    blocks: Chunk[SlackBlock] = Chunk.empty
) derives CanEqual
