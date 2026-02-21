package kyo.http2

import kyo.Frame
import kyo.KyoException
import kyo.Text

sealed abstract class HttpError(message: Text, cause: Text | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object HttpError:

    case class ParseError(message: String)(using Frame)
        extends HttpError(s"Failed to parse: $message")

end HttpError
