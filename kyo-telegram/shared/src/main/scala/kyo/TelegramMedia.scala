package kyo

/** The media a message can carry, as Telegram describes each file it stores.
  *
  * Every file has a [[kyo.TelegramId.FileId]], which resends it (as `TelegramInputFile.Id`) or reads its
  * download path (`Telegram.getFile`), and a [[kyo.TelegramId.FileUniqueId]], which is the same file
  * for every bot and over time but can do neither. Sizes are `ByteSize`, which holds more than 2^31
  * bytes; a size is `Absent` when Telegram omits it or sends a negative count. Durations are whole seconds.
  *
  * A photo arrives as several sizes of one image, smallest first, so a message's photo is a `Chunk` of
  * [[kyo.TelegramMedia.PhotoSize]].
  *
  * @see
  *   [[kyo.TelegramMessage.Content]] where media arrives
  * @see
  *   [[kyo.TelegramInputFile]] how media is sent
  */
object TelegramMedia:

    /** One size of a photo. */
    final case class PhotoSize(
        fileId: TelegramId.FileId,
        fileUniqueId: TelegramId.FileUniqueId,
        width: Int,
        height: Int,
        fileSize: Maybe[ByteSize] = Absent
    ) derives CanEqual

    object PhotoSize:
        inline given Schema[PhotoSize] =
            compiletime.error("TelegramMedia.PhotoSize has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** A general file. */
    final case class Document(
        fileId: TelegramId.FileId,
        fileUniqueId: TelegramId.FileUniqueId,
        fileName: Maybe[String] = Absent,
        mimeType: Maybe[String] = Absent,
        fileSize: Maybe[ByteSize] = Absent
    ) derives CanEqual

    object Document:
        inline given Schema[Document] =
            compiletime.error("TelegramMedia.Document has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** A music file, which Telegram clients play as audio. */
    final case class Audio(
        fileId: TelegramId.FileId,
        fileUniqueId: TelegramId.FileUniqueId,
        duration: Duration,
        performer: Maybe[String] = Absent,
        title: Maybe[String] = Absent,
        fileName: Maybe[String] = Absent,
        mimeType: Maybe[String] = Absent,
        fileSize: Maybe[ByteSize] = Absent
    ) derives CanEqual

    object Audio:
        inline given Schema[Audio] = compiletime.error("TelegramMedia.Audio has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** A video file. */
    final case class Video(
        fileId: TelegramId.FileId,
        fileUniqueId: TelegramId.FileUniqueId,
        width: Int,
        height: Int,
        duration: Duration,
        fileName: Maybe[String] = Absent,
        mimeType: Maybe[String] = Absent,
        fileSize: Maybe[ByteSize] = Absent
    ) derives CanEqual

    object Video:
        inline given Schema[Video] = compiletime.error("TelegramMedia.Video has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** A voice note. */
    final case class Voice(
        fileId: TelegramId.FileId,
        fileUniqueId: TelegramId.FileUniqueId,
        duration: Duration,
        mimeType: Maybe[String] = Absent,
        fileSize: Maybe[ByteSize] = Absent
    ) derives CanEqual

    object Voice:
        inline given Schema[Voice] = compiletime.error("TelegramMedia.Voice has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** A point on the map, in degrees. */
    final case class Location(latitude: Double, longitude: Double) derives CanEqual

    object Location:
        inline given Schema[Location] =
            compiletime.error("TelegramMedia.Location has no Schema: kyo-telegram decodes Telegram's payloads itself")

end TelegramMedia
