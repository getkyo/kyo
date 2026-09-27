package kyo

/** A file ready to download, as `Telegram.getFile` answers it.
  *
  * `path` is where `Telegram.download` fetches it from. Telegram guarantees the path works for at least
  * an hour; after that `getFile` gives a new one. A bot may download files of up to 20 MB from
  * Telegram's servers: `getFile` for a larger one fails with [[kyo.TelegramFileTooBigException]].
  *
  * Note: on a self-hosted Bot API server `path` is an absolute path on that server's disk, which
  * `download` cannot fetch over HTTP; read the file from that disk instead.
  *
  * @see
  *   [[kyo.Telegram.getFile]] how a file is looked up
  * @see
  *   [[kyo.Telegram.download]] how it is fetched
  */
final case class TelegramFile(
    id: TelegramId.FileId,
    uniqueId: TelegramId.FileUniqueId,
    size: Maybe[ByteSize] = Absent,
    path: Maybe[String] = Absent
) derives CanEqual

object TelegramFile:
    inline given Schema[TelegramFile] = compiletime.error("TelegramFile has no Schema: kyo-telegram decodes Telegram's payloads itself")
