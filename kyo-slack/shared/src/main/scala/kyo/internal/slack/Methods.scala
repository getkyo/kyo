package kyo.internal.slack

import kyo.*

/** The request and answer objects of the Web API methods the module calls, each named for its method. A method's
  * object holds its wire fields, not a second model of a public type: its fields are the method's arguments and results, typed
  * with the public types.
  *
  * A field is named by its wire key rather than renamed: kyo-schema names a missing renamed field by its Scala name, and a
  * decode failure must name the key Slack left out.
  */
private[kyo] object Methods:

    /** `auth.test` takes no argument. */
    final case class AuthTest() derives Schema

    /** `auth.test`'s answer. Slack documents `bot_id` only for a bot token, the one token the module sends. */
    final case class AuthTestAnswer(
        user_id: SlackId.UserId,
        team_id: SlackId.TeamId,
        bot_id: SlackId.BotId,
        url: String
    ) derives Schema

    /** `apps.connections.open` takes no argument. */
    final case class AppsConnectionsOpen() derives Schema

    /** `apps.connections.open`'s answer: the Socket Mode url, required, so an ok answer without it fails at `url`. */
    final case class AppsConnectionsOpenAnswer(url: String) derives Schema

    final case class ChatPostMessageAnswer(ts: SlackTs) derives Schema

    /** `chat.postEphemeral` answers `message_ts`, not `ts`. */
    final case class ChatPostEphemeralAnswer(message_ts: SlackTs) derives Schema

    final case class ChatUpdateAnswer(ts: SlackTs) derives Schema

    /** The view the `views.open`, `views.update` and `views.publish` answers carry, by its id. */
    final case class ViewRef(id: SlackId.ViewId) derives Schema

    final case class ViewsOpenAnswer(view: ViewRef) derives Schema

    final case class ViewsUpdateAnswer(view: ViewRef) derives Schema

    final case class ViewsPublishAnswer(view: ViewRef) derives Schema

    /** `files.info` takes the file's id as a form parameter. */
    final case class FilesInfo(file: SlackId.FileId) derives HttpFormCodec

    /** `files.info`'s answer: the file object whole, as a message event would carry it. */
    final case class FilesInfoAnswer(file: SlackFile) derives Schema

    /** `files.getUploadURLExternal` takes the file's name and its size in bytes as form parameters. */
    final case class FilesGetUploadUrl(filename: String, length: Long) derives HttpFormCodec

    /** `files.getUploadURLExternal`'s answer: where the file's bytes go, and the id the file will have. */
    final case class FilesGetUploadUrlAnswer(upload_url: String, file_id: SlackId.FileId) derives Schema

    /** `files.completeUploadExternal` takes the uploaded files and where to share them, with the message's text and thread. */
    final case class FilesCompleteUpload(
        files: Chunk[FilesCompleteUpload.Entry],
        channel_id: SlackId.ChannelId,
        initial_comment: Maybe[String],
        thread_ts: Maybe[SlackTs]
    ) derives Schema

    object FilesCompleteUpload:
        final case class Entry(id: SlackId.FileId, title: String) derives Schema

    /** `files.completeUploadExternal`'s answer: the files it completed, without the message that shares them. */
    final case class FilesCompleteUploadAnswer(files: Chunk[FilesCompleteUpload.Entry]) derives Schema

    /** `files.info`'s answer read for the file's shares alone: the messages that share it, by channel, in public and private
      * conversations.
      */
    final case class FilesInfoShares(file: FilesInfoShares.File) derives Schema

    object FilesInfoShares:
        final case class File(shares: Maybe[Shares]) derives Schema
        final case class Shares(public: Maybe[Dict[String, Chunk[Share]]], `private`: Maybe[Dict[String, Chunk[Share]]]) derives Schema
        final case class Share(ts: SlackTs) derives Schema
    end FilesInfoShares

end Methods
