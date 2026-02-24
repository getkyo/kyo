package kyo.http2

import kyo.Duration
import kyo.Frame
import kyo.KyoException
import kyo.Text

sealed abstract class HttpError(message: Text, cause: Text | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object HttpError:

    case class ParseError(message: String)(using Frame)
        extends HttpError(s"Failed to parse: $message")

    case class TimeoutError(duration: Duration)(using Frame)
        extends HttpError(s"Request timed out after ${duration.show}")

    case class TooManyRedirects(count: Int)(using Frame)
        extends HttpError(s"Too many redirects: $count")

    case class ConnectionPoolExhausted(host: String, port: Int, maxConnections: Int)(using Frame)
        extends HttpError(s"Connection pool exhausted for $host:$port (max $maxConnections)")

    case class ConnectionError(message: String, cause: Throwable)(using Frame)
        extends HttpError(message, cause)

end HttpError
