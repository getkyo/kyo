package kyo

/** The answer to a callback query: what the user sees after pressing the button.
  *
  * With no `text`, the answer only stops the button's progress indicator. `text` shows a short
  * notification at the top of the chat (up to 200 characters), or an alert the user must dismiss when
  * `showAlert` is set. `url` opens a game or a `t.me` link to the bot. `cacheTime` lets Telegram
  * clients reuse this answer for that long without asking the bot again.
  *
  * IMPORTANT: a `text` over 200 characters, and a `cacheTime` that is not a whole number of seconds
  * up to `Int.MaxValue`, are refused at construction with a [[kyo.TelegramInvalidCallbackAnswerException]]:
  * Telegram takes the cache time in whole seconds, and a fraction is refused rather than rounded.
  *
  * @see
  *   [[kyo.Telegram.answerCallback]] the operation
  */
final case class TelegramCallbackAnswer(
    text: Maybe[String] = Absent,
    showAlert: Boolean = false,
    url: Maybe[TelegramUrl] = Absent,
    cacheTime: Maybe[Duration] = Absent
)(using Frame) derives CanEqual:
    text.filter(_.length > 200).foreach(t =>
        throw TelegramInvalidCallbackAnswerException(TelegramInvalidCallbackAnswerException.Problem.TextLength(t.length))
    )
    cacheTime.filter(t => !TelegramConfig.wholeSeconds(t)).foreach(t =>
        throw TelegramInvalidCallbackAnswerException(TelegramInvalidCallbackAnswerException.Problem.CacheTime(t))
    )
end TelegramCallbackAnswer

object TelegramCallbackAnswer:
    /** No text: only stops the progress indicator. */
    // A constant of the kyo package has no caller Frame, and an empty answer passes every check.
    val empty: TelegramCallbackAnswer = TelegramCallbackAnswer()(using Frame.internal)
end TelegramCallbackAnswer
