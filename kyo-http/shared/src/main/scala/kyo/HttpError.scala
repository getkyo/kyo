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
        def safeMessage(e: Throwable): String =
            val msg = e.getMessage
            if msg != null then msg else e.getClass.getName
        cause match
            case e: ConnectException => HttpError.ConnectionFailed(host, port, e)
            case e: TimeoutException => HttpError.Timeout(safeMessage(e))
            case e: SSLException     => HttpError.SslError(safeMessage(e), e)
            case e                   => HttpError.InvalidResponse(safeMessage(e))
        end match
    end fromThrowable
end HttpError
