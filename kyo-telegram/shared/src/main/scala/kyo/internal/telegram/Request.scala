package kyo.internal.telegram

import kyo.*
import kyo.schema.omit
import kyo.schema.rename
import kyo.schema.transform

/** The body of each Bot API method the module calls, as a record of the public inputs that method takes. Each schema
  * names the fields as Telegram does and flattens an input record into the body, so the body is the method's JSON
  * object with no field written by hand.
  */
private[kyo] object Request:

    /** A text as Telegram takes it: plain text with its entities, or markup rendered to text with the parse mode that reads it. */
    final case class Rendered(
        text: String,
        @omit entities: Chunk[Telegram.Entity] = Chunk.empty,
        @omit parseMode: Maybe[String] = Absent
    )

    object Rendered:
        given Schema[Rendered] = WireField.snakeCase(Schema.derived[Rendered])

        def of(text: Telegram.Text): Rendered =
            text match
                case Telegram.Text.Plain(text, entities) => Rendered(text, entities)
                case Telegram.Text.MarkdownV2(markup)    => Rendered(Markup.markdownV2(markup), parseMode = Present("MarkdownV2"))
                case Telegram.Text.Html(markup)          => Rendered(Markup.html(markup), parseMode = Present("HTML"))
    end Rendered

    /** A media caption: a [[Rendered]] text under the caption's own keys. */
    final case class Caption(
        @rename("caption") text: String,
        @rename("caption_entities") @omit entities: Chunk[Telegram.Entity] = Chunk.empty,
        @rename("parse_mode") @omit parseMode: Maybe[String] = Absent
    ) derives Schema

    object Caption:
        def of(text: Telegram.Text): Caption =
            val rendered = Rendered.of(text)
            Caption(rendered.text, rendered.entities, rendered.parseMode)
    end Caption

    /** `link_preview_options`, sent only to turn the preview off. */
    final case class LinkPreviewOptions(isDisabled: Boolean)

    object LinkPreviewOptions:
        given Schema[LinkPreviewOptions] = WireField.snakeCase(Schema.derived[LinkPreviewOptions])

        def of(linkPreview: Boolean): Maybe[LinkPreviewOptions] = if linkPreview then Absent else Present(LinkPreviewOptions(true))
    end LinkPreviewOptions

    final case class SendMessage(
        chatId: Telegram.Chat.Target,
        text: Rendered,
        @omit linkPreviewOptions: Maybe[LinkPreviewOptions],
        options: Telegram.SendOptions
    )

    object SendMessage:
        given Schema[SendMessage] = WireField.snakeCase(Schema[SendMessage].flatten(_.text).flatten(_.options))

    // One record per media method, since each names its file field after the media: the file's id or URL, or absent when the
    // file is uploaded as a part of the same name.

    final case class SendPhoto(
        chatId: Telegram.Chat.Target,
        @omit photo: Maybe[String],
        caption: Maybe[Caption],
        options: Telegram.SendOptions
    )

    object SendPhoto:
        given Schema[SendPhoto] = WireField.snakeCase(Schema[SendPhoto].flatten(_.caption).flatten(_.options))

    final case class SendDocument(
        chatId: Telegram.Chat.Target,
        @omit document: Maybe[String],
        caption: Maybe[Caption],
        options: Telegram.SendOptions
    )

    object SendDocument:
        given Schema[SendDocument] = WireField.snakeCase(Schema[SendDocument].flatten(_.caption).flatten(_.options))

    final case class SendAudio(
        chatId: Telegram.Chat.Target,
        @omit audio: Maybe[String],
        caption: Maybe[Caption],
        options: Telegram.SendOptions
    )

    object SendAudio:
        given Schema[SendAudio] = WireField.snakeCase(Schema[SendAudio].flatten(_.caption).flatten(_.options))

    final case class SendVideo(
        chatId: Telegram.Chat.Target,
        @omit video: Maybe[String],
        caption: Maybe[Caption],
        options: Telegram.SendOptions
    )

    object SendVideo:
        given Schema[SendVideo] = WireField.snakeCase(Schema[SendVideo].flatten(_.caption).flatten(_.options))

    final case class SendVoice(
        chatId: Telegram.Chat.Target,
        @omit voice: Maybe[String],
        caption: Maybe[Caption],
        options: Telegram.SendOptions
    )

    object SendVoice:
        given Schema[SendVoice] = WireField.snakeCase(Schema[SendVoice].flatten(_.caption).flatten(_.options))

    final case class SendLocation(chatId: Telegram.Chat.Target, latitude: Double, longitude: Double, options: Telegram.SendOptions)

    object SendLocation:
        given Schema[SendLocation] = WireField.snakeCase(Schema[SendLocation].flatten(_.options))

    final case class EditMessageText(
        chatId: Telegram.Chat.Target,
        messageId: Telegram.MessageId,
        text: Rendered,
        @omit linkPreviewOptions: Maybe[LinkPreviewOptions],
        @omit replyMarkup: Maybe[Telegram.Keyboard.Inline]
    )

    object EditMessageText:
        given Schema[EditMessageText] = WireField.snakeCase(Schema[EditMessageText].flatten(_.text))

    final case class EditMessageCaption(
        chatId: Telegram.Chat.Target,
        messageId: Telegram.MessageId,
        caption: Maybe[Caption],
        @omit replyMarkup: Maybe[Telegram.Keyboard.Inline]
    )

    object EditMessageCaption:
        given Schema[EditMessageCaption] = WireField.snakeCase(Schema[EditMessageCaption].flatten(_.caption))

    final case class EditMessageReplyMarkup(
        chatId: Telegram.Chat.Target,
        messageId: Telegram.MessageId,
        @omit replyMarkup: Maybe[Telegram.Keyboard.Inline]
    )

    object EditMessageReplyMarkup:
        given Schema[EditMessageReplyMarkup] = WireField.snakeCase(Schema.derived[EditMessageReplyMarkup])

    final case class DeleteMessage(chatId: Telegram.Chat.Target, messageId: Telegram.MessageId)

    object DeleteMessage:
        given Schema[DeleteMessage] = WireField.snakeCase(Schema.derived[DeleteMessage])

    final case class AnswerCallbackQuery(callbackQueryId: Telegram.CallbackQueryId, answer: Telegram.CallbackAnswer)

    object AnswerCallbackQuery:
        given Schema[AnswerCallbackQuery] = WireField.snakeCase(Schema[AnswerCallbackQuery].flatten(_.answer))

    final case class SendChatAction(
        chatId: Telegram.Chat.Target,
        action: Telegram.ChatAction,
        @omit messageThreadId: Maybe[Telegram.MessageThreadId]
    )

    object SendChatAction:
        given Schema[SendChatAction] = WireField.snakeCase(Schema.derived[SendChatAction])

    final case class SetMessageReaction(
        chatId: Telegram.Chat.Target,
        messageId: Telegram.MessageId,
        reaction: Chunk[Telegram.Reaction.Sendable],
        isBig: Boolean
    )

    object SetMessageReaction:
        given Schema[SetMessageReaction] = WireField.snakeCase(Schema.derived[SetMessageReaction])

    final case class SetMyCommands(
        commands: Chunk[Telegram.Command],
        @omit scope: Maybe[Telegram.Command.Scope],
        @omit languageCode: Maybe[String]
    )

    object SetMyCommands:
        given Schema[SetMyCommands] = WireField.snakeCase(Schema.derived[SetMyCommands])

    final case class GetFile(fileId: Telegram.FileId)

    object GetFile:
        given Schema[GetFile] = WireField.snakeCase(Schema.derived[GetFile])

    final case class SetWebhook(
        @transform(WireField.UrlText) url: HttpUrl,
        secretToken: Telegram.SecretToken,
        options: Telegram.WebhookOptions
    )

    object SetWebhook:
        given Schema[SetWebhook] = WireField.snakeCase(Schema[SetWebhook].flatten(_.options))

    final case class DeleteWebhook(dropPendingUpdates: Boolean)

    object DeleteWebhook:
        given Schema[DeleteWebhook] = WireField.snakeCase(Schema.derived[DeleteWebhook])

    final case class GetUpdates(
        @omit offset: Maybe[Long],
        @transform(WireField.Seconds) timeout: Duration,
        limit: Int,
        @omit allowedUpdates: Maybe[Chunk[Telegram.Update.Type]]
    )

    object GetUpdates:
        given Schema[GetUpdates] = WireField.snakeCase(Schema.derived[GetUpdates])

    /** The body of a method that takes no parameters. */
    final case class NoParameters() derives Schema

end Request
