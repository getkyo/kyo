package kyo

/** A file to send, in one of the three ways Telegram accepts one.
  *
  *   - `Id`: a file already on Telegram's servers, by the id a message or `getFile` gave. No size limit,
  *     but the kind cannot change: a video's id cannot be sent as a photo.
  *   - `Url`: Telegram downloads it. Up to 5 MB for photos and 20 MB for other kinds, and the server must
  *     answer the right MIME type; `sendDocument` by URL works only for PDF and ZIP.
  *   - `Upload`: the bytes, sent as multipart form data. Up to 10 MB for photos and 50 MB for other
  *     kinds on Telegram's servers. `name` is the file name Telegram shows.
  *
  * The limits are Telegram's (Bot API, "Sending files"); a file over them is refused by Telegram.
  *
  * An `Upload` compares its bytes element by element, since `Span` itself compares by reference, so two
  * uploads of the same content are equal, and so are the messages that carry them.
  *
  * @see
  *   [[kyo.TelegramContent]] the media kinds that take a file
  */
sealed trait TelegramInputFile derives CanEqual

object TelegramInputFile:

    final case class Id(file: TelegramId.FileId) extends TelegramInputFile

    final case class Url(url: HttpUrl) extends TelegramInputFile

    final case class Upload(name: String, bytes: Span[Byte], contentType: Maybe[String] = Absent) extends TelegramInputFile:
        override def equals(other: Any): Boolean =
            other match
                case that: Upload => that.name == name && that.contentType == contentType && that.bytes.is(bytes)
                case _            => false

        override def hashCode: Int = (name, contentType, bytes.hash).##
    end Upload
end TelegramInputFile
