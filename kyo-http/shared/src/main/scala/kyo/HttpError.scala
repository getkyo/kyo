package kyo

import java.net.ConnectException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** Error types for HTTP client operations. Extends KyoException for enhanced error reporting. */
sealed abstract class HttpError(message: Text, cause: Text | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object HttpError:

    /** Connection to server failed. */
    case class ConnectionFailed(host: String, port: Int, cause: Throwable)(using Frame)
        extends HttpError(s"Connection to $host:$port failed", cause)

    /** Request timed out. */
    case class Timeout(message: String)(using Frame)
        extends HttpError(s"Request timed out: $message")

    /** SSL/TLS error during connection. */
    case class SslError(message: String, cause: Throwable)(using Frame)
        extends HttpError(s"SSL/TLS error: $message", cause)

    /** Maximum redirect limit exceeded. */
    case class TooManyRedirects(count: Int)(using Frame)
        extends HttpError(s"Maximum redirect limit exceeded: $count redirects")

    /** Invalid or unexpected response from server. */
    case class InvalidResponse(message: String)(using Frame)
        extends HttpError(s"Invalid response: $message")

    /** HTTP status error (4xx/5xx) from typed convenience methods. */
    case class StatusError(status: HttpResponse.Status, body: String)(using Frame)
        extends HttpError(s"HTTP ${status.code}", body)

    /** Failed to parse response body. */
    case class ParseError(message: String, cause: Throwable)(using Frame)
        extends HttpError(s"Failed to parse response: $message", cause)

    /** Retry schedule exhausted while still getting retriable responses. */
    case class RetriesExhausted(attempts: Int, lastStatus: HttpResponse.Status, lastBody: String)(using Frame)
        extends HttpError(s"Retries exhausted after $attempts attempts (last status: ${lastStatus.code})", lastBody)

    /** Convert a Throwable to an HttpError based on its type. */
    private[kyo] def fromThrowable(cause: Throwable, host: String, port: Int)(using Frame): HttpError =
        def safeMessage(e: Throwable): String =
            val msg = e.getMessage
            if msg != null then msg else e.getClass.getName
        cause match
            case e: ConnectException => ConnectionFailed(host, port, e)
            // TODO there's kyo.Timeout as well I think
            case e: TimeoutException => Timeout(safeMessage(e))
            case e: SSLException     => SslError(safeMessage(e), e)
            case e                   => InvalidResponse(safeMessage(e))
        end match
    end fromThrowable

end HttpError
