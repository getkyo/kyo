package kyo

import kyo.internal.slack.WireField
import kyo.schema.rename
import kyo.schema.transform

/** A file a message shares, as the message event carries it (`reference/objects/file-object`): its id, which `files.info` takes,
  * its name, which Slack leaves `null` on an unnamed file, its media type, its size, and `url`, Slack's `url_private`, whose bytes
  * `Slack.download` reads with the bot's token. Slack sends a Slack Connect file with its id alone, and
  * `"file_access": "check_file_info"`, until the app calls `files.info`, so the id is the one field it is sure to carry.
  *
  * A URL in this value is metadata, not permission to send a credential to it. `Slack.download` checks the configured file
  * origins before attaching the bot token. Reading metadata never downloads the file's bytes.
  *
  * @see [[kyo.Slack.file]] Resolves metadata by file id
  * @see [[kyo.Slack.download]] Reads the authenticated file contents
  * @see [[kyo.SlackEvent.Message]] Carries files shared by a message
  */
final case class SlackFile(
    id: SlackId.FileId,
    name: Maybe[String] = Absent,
    mimetype: Maybe[String] = Absent,
    @transform(WireField.MaybeSize) size: Maybe[ByteSize] = Absent,
    @rename("url_private") @transform(WireField.MaybeUrl) url: Maybe[HttpUrl] = Absent
) derives CanEqual, Schema
