package kyo

/** What an answer through a `response_url` says: its text and, optionally, Block Kit blocks.
  *
  * The four `response_url` operations of [[kyo.Slack]] send it: `respondEphemeral` and `respondInChannel`
  * post it as a new message, `replaceOriginal` puts it in place of the message the interaction came from.
  * It names no channel, because the `response_url` already says where the answer goes, and no thread,
  * which only `respondInChannel` takes. `blocks` render through the same encoder as a posted message's,
  * and `text` is the notification and fallback text Slack shows when blocks cannot be.
  *
  * {{{
  * Slack.replaceOriginal(url, SlackReply("Done", Chunk(SlackBlock.Section(SlackBlock.Text.Markdown("*Done*")))))
  * }}}
  */
final case class SlackReply(text: String, blocks: Chunk[SlackBlock] = Chunk.empty) derives CanEqual
