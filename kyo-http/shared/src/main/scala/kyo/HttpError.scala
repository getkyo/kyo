package kyo

import java.util.concurrent.TimeoutException
import scala.annotation.tailrec

/** Sealed error hierarchy representing failure modes in the HTTP client pipeline.
  *
  * Each variant captures a specific failure mode: connection failures, timeouts, SSL errors, redirect limits, invalid responses, HTTP
  * status errors, parse errors, and retry exhaustion. All extend `KyoException` for zero-cost stack traces and `Frame`-based context. Used
  * as the error type in `Abort[HttpError]` throughout the client API.
  *
  *   - `ConnectionFailed` — TCP/TLS connection to host failed
  *   - `Timeout` — Request or connect timeout exceeded
  *   - `SslError` — SSL/TLS handshake or certificate error
  *   - `TooManyRedirects` — Exceeded configured redirect limit
  *   - `InvalidResponse` — Unexpected or malformed response
  *   - `StatusError` — Error status (4xx/5xx) from typed convenience methods (carries status + body)
  *   - `ParseError` — Response body deserialization failed
  *   - `RetriesExhausted` — Retry schedule exhausted while still getting retriable responses
  *
  * @see
  *   [[kyo.HttpClient]]
  * @see
  *   [[kyo.KyoException]]
  * @see
  *   [[kyo.Abort]]
  */
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
    case class StatusError(status: HttpStatus, body: String)(using Frame)
        extends HttpError(s"HTTP ${status.code}", body)

    /** Failed to parse response body. */
    case class ParseError(message: String, cause: Throwable)(using Frame)
        extends HttpError(s"Failed to parse response: $message", cause)

    object ParseError:
        def apply(message: String)(using Frame): ParseError = ParseError(message, null)
    end ParseError

    /** Retry schedule exhausted while still getting retriable responses. */
    case class RetriesExhausted(attempts: Int, lastStatus: HttpStatus, lastBody: String)(using Frame)
        extends HttpError(s"Retries exhausted after $attempts attempts (last status: ${lastStatus.code})", lastBody)

    /** Missing required authentication credential. */
    case class MissingAuth(name: String)(using Frame)
        extends HttpError(s"Missing required authentication: $name")

    /** Invalid authentication credential format. */
    case class InvalidAuth(message: String)(using Frame)
        extends HttpError(s"Invalid authentication: $message")

    /** Missing required request parameter (query, header, or cookie). */
    case class MissingParam(message: String)(using Frame)
        extends HttpError(s"Missing required parameter: $message")

    /** Convert a Throwable to an HttpError based on its type. */
    private[kyo] def fromThrowable(cause: Throwable, host: String, port: Int)(using Frame): HttpError =
        def safeMessage(e: Throwable): String =
            val msg = e.getMessage
            if msg != null then msg else e.getClass.getName
        cause match
            case e if isConnectException(e) => ConnectionFailed(host, port, e)
            case e: TimeoutException        => Timeout(safeMessage(e))
            case e: kyo.Timeout             => Timeout(safeMessage(e))
            case e if isSslException(e)     => SslError(safeMessage(e), e)
            case e                          => InvalidResponse(safeMessage(e))
        end match
    end fromThrowable

    private def isConnectException(e: Throwable): Boolean =
        hasClassInChain(e, "ConnectException")

    private def isSslException(e: Throwable): Boolean =
        hasClassInChain(e, "javax.net.ssl.")

    /** Check if exception or its cause chain has a class whose simple name contains the target. */
    private def hasClassInChain(e: Throwable, simpleName: String): Boolean =
        @tailrec def loop(t: Maybe[Throwable]): Boolean =
            t match
                case Present(t) if t.getClass.getName.contains(simpleName) => true
                case Present(t)                                            => loop(Maybe(t.getCause))
                case Absent                                                => false
        loop(Present(e))
    end hasClassInChain

end HttpError
