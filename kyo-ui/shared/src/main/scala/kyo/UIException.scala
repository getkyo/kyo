package kyo

/** Base type for kyo-ui errors, so callers can distinguish UI failures from arbitrary `KyoException`s. */
class UIException(message: => Text = "", cause: Text | Throwable = "")(using Frame) extends KyoException(message, cause)
