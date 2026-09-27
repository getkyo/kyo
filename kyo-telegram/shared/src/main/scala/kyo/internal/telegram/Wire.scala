package kyo.internal.telegram

import kyo.*

/** The Bot API's JSON shapes, named as Telegram names them. Only what the public model reads is
  * declared; kyo-schema skips every other field. A field Telegram marks optional has a default, so
  * its absence decodes, and a field it always sends has none, so its absence is a decode failure.
  *
  * An update's kind field is kept as a `Structure.Value` and decoded on its own, so an update whose
  * kind does not decode becomes `TelegramUpdate.Unknown` with the whole update's JSON instead of
  * failing the batch. A message nested in it (a reply, the message under a callback button) is also
  * kept as a `Structure.Value`, because the model reads it with the same decoder as a top-level
  * message; one that does not decode fails its parent, so the update is `Unknown`.
  */
private[kyo] object Wire:

    final case class ResponseParameters(
        migrate_to_chat_id: Maybe[Long] = Absent,
        retry_after: Maybe[Long] = Absent
    ) derives Schema

    final case class Envelope(
        ok: Boolean,
        result: Maybe[Structure.Value] = Absent,
        error_code: Maybe[Int] = Absent,
        description: Maybe[String] = Absent,
        parameters: Maybe[ResponseParameters] = Absent
    ) derives Schema

    final case class User(
        id: Long,
        is_bot: Boolean,
        first_name: String,
        last_name: Maybe[String] = Absent,
        username: Maybe[String] = Absent,
        language_code: Maybe[String] = Absent
    ) derives Schema

    final case class Chat(
        id: Long,
        `type`: String,
        title: Maybe[String] = Absent,
        username: Maybe[String] = Absent,
        first_name: Maybe[String] = Absent,
        last_name: Maybe[String] = Absent,
        is_forum: Maybe[Boolean] = Absent
    ) derives Schema

    final case class Entity(
        `type`: String,
        offset: Int,
        length: Int,
        url: Maybe[String] = Absent,
        user: Maybe[User] = Absent,
        language: Maybe[String] = Absent,
        custom_emoji_id: Maybe[String] = Absent
    ) derives Schema

    final case class PhotoSize(
        file_id: String,
        file_unique_id: String,
        width: Int,
        height: Int,
        file_size: Maybe[Long] = Absent
    ) derives Schema

    final case class Document(
        file_id: String,
        file_unique_id: String,
        file_name: Maybe[String] = Absent,
        mime_type: Maybe[String] = Absent,
        file_size: Maybe[Long] = Absent
    ) derives Schema

    final case class Audio(
        file_id: String,
        file_unique_id: String,
        duration: Int,
        performer: Maybe[String] = Absent,
        title: Maybe[String] = Absent,
        file_name: Maybe[String] = Absent,
        mime_type: Maybe[String] = Absent,
        file_size: Maybe[Long] = Absent
    ) derives Schema

    final case class Video(
        file_id: String,
        file_unique_id: String,
        width: Int,
        height: Int,
        duration: Int,
        file_name: Maybe[String] = Absent,
        mime_type: Maybe[String] = Absent,
        file_size: Maybe[Long] = Absent
    ) derives Schema

    final case class Voice(
        file_id: String,
        file_unique_id: String,
        duration: Int,
        mime_type: Maybe[String] = Absent,
        file_size: Maybe[Long] = Absent
    ) derives Schema

    final case class Location(latitude: Double, longitude: Double) derives Schema

    final case class Message(
        message_id: Int,
        date: Long,
        chat: Chat,
        from: Maybe[User] = Absent,
        sender_chat: Maybe[Chat] = Absent,
        message_thread_id: Maybe[Int] = Absent,
        edit_date: Maybe[Long] = Absent,
        reply_to_message: Maybe[Structure.Value] = Absent,
        text: Maybe[String] = Absent,
        entities: Chunk[Entity] = Chunk.empty,
        caption: Maybe[String] = Absent,
        caption_entities: Chunk[Entity] = Chunk.empty,
        photo: Maybe[Chunk[PhotoSize]] = Absent,
        document: Maybe[Document] = Absent,
        audio: Maybe[Audio] = Absent,
        video: Maybe[Video] = Absent,
        voice: Maybe[Voice] = Absent,
        location: Maybe[Location] = Absent
    ) derives Schema

    /** What every message, accessible or not, carries. An inaccessible message's `date` is always 0. */
    final case class MessageHead(chat: Chat, message_id: Int, date: Long) derives Schema

    final case class CallbackQuery(
        id: String,
        from: User,
        chat_instance: String,
        message: Maybe[Structure.Value] = Absent,
        inline_message_id: Maybe[String] = Absent,
        data: Maybe[String] = Absent
    ) derives Schema

    final case class ChatMember(status: String, user: User) derives Schema

    final case class ChatMemberUpdated(
        chat: Chat,
        from: User,
        date: Long,
        old_chat_member: ChatMember,
        new_chat_member: ChatMember
    ) derives Schema

    final case class ReactionType(
        `type`: String,
        emoji: Maybe[String] = Absent,
        custom_emoji_id: Maybe[String] = Absent
    ) derives Schema

    final case class MessageReactionUpdated(
        chat: Chat,
        message_id: Int,
        date: Long,
        old_reaction: Chunk[ReactionType],
        new_reaction: Chunk[ReactionType],
        user: Maybe[User] = Absent,
        actor_chat: Maybe[Chat] = Absent
    ) derives Schema

    /** An update's id and the one field that says its kind, each kept as JSON to decode on its own. */
    final case class UpdateHead(
        update_id: Long,
        message: Maybe[Structure.Value] = Absent,
        edited_message: Maybe[Structure.Value] = Absent,
        channel_post: Maybe[Structure.Value] = Absent,
        edited_channel_post: Maybe[Structure.Value] = Absent,
        callback_query: Maybe[Structure.Value] = Absent,
        my_chat_member: Maybe[Structure.Value] = Absent,
        chat_member: Maybe[Structure.Value] = Absent,
        message_reaction: Maybe[Structure.Value] = Absent
    ) derives Schema

    final case class File(
        file_id: String,
        file_unique_id: String,
        file_size: Maybe[Long] = Absent,
        file_path: Maybe[String] = Absent
    ) derives Schema

    final case class WebhookInfo(
        url: String,
        has_custom_certificate: Boolean,
        pending_update_count: Int,
        ip_address: Maybe[String] = Absent,
        last_error_date: Maybe[Long] = Absent,
        last_error_message: Maybe[String] = Absent,
        max_connections: Maybe[Int] = Absent,
        allowed_updates: Chunk[String] = Chunk.empty
    ) derives Schema

end Wire
