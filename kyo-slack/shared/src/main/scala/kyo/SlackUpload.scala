package kyo

/** A file's display name and bytes for [[kyo.Slack.upload]].
  *
  * The bytes are already in memory. Constructing this value performs no upload; `Slack.upload` sends each file and shares them
  * together in the requested channel or thread. Slack determines the media type from the filename.
  *
  * @see [[kyo.Slack.upload]] Uploads and shares files
  * @see [[kyo.SlackFile]] Metadata for a file already on Slack
  * @see [[kyo.SlackConfig]] File origins and request bounds
  */
final case class SlackUpload(name: String, bytes: Span[Byte]) derives CanEqual
