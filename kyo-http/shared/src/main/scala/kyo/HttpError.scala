package kyo

import java.net.ConnectException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/** Error types for HTTP client operations. */
enum HttpError:
    case ConnectionFailed(host: String, port: Int, cause: Throwable)
    case Timeout(message: String)
    case SslError(message: String, cause: Throwable)
    case TooManyRedirects(count: Int)
    case InvalidResponse(message: String)
end HttpError

object HttpError:
    /** Convert a Throwable to an HttpError based on its type. */
    private[kyo] def fromThrowable(cause: Throwable, host: String, port: Int): HttpError =
        cause match
            case e: ConnectException => HttpError.ConnectionFailed(host, port, e)
            case e: TimeoutException => HttpError.Timeout(e.getMessage)
            case e: SSLException     => HttpError.SslError(e.getMessage, e)
            case e                   => HttpError.InvalidResponse(e.getMessage)
end HttpError
