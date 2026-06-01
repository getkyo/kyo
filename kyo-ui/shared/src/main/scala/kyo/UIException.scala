package kyo

/** Base type for kyo-ui errors, so callers can distinguish UI failures from arbitrary `KyoException`s. */
class UIException(message: => String = "", cause: String | Throwable = "")(using Frame) extends KyoException(message, cause)
