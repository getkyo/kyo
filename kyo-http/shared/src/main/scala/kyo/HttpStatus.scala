package kyo

import kyo.*
import scala.annotation.tailrec

/** HTTP response status code with category classification.
  *
  * A sealed hierarchy covering all standard HTTP status codes, organized into five enums — `Informational` (1xx), `Success` (2xx),
  * `Redirect` (3xx), `ClientError` (4xx), and `ServerError` (5xx) — plus a `Custom` case for non-standard codes. All standard cases are
  * exported to the companion object so they can be referenced directly as `HttpStatus.OK`, `HttpStatus.NotFound`, etc.
  *
  * Use `HttpStatus(code)` to resolve an integer to its known enum case, or wrap it in `Custom` when no standard case matches. Use
  * `HttpStatus.resolve(code)` when you want to express absence rather than fall back to `Custom`.
  *
  * The classification predicates (`isSuccess`, `isClientError`, etc.) are useful for writing retry policies and filter conditions without
  * matching on concrete cases.
  *
  * @see
  *   [[kyo.HttpResponse]] Carries a status code
  * @see
  *   [[kyo.HttpRoute]] Maps error types to status codes via `.error[E](status)`
  * @see
  *   [[kyo.HttpClientConfig.retryOn]] Retry predicate that receives an `HttpStatus`
  */
sealed abstract class HttpStatus(val code: Int) derives CanEqual:
    def isInformational: Boolean = code >= 100 && code < 200
    def isSuccess: Boolean       = code >= 200 && code < 300
    def isRedirect: Boolean      = code >= 300 && code < 400
    def isClientError: Boolean   = code >= 400 && code < 500
    def isServerError: Boolean   = code >= 500 && code < 600
    def isError: Boolean         = code >= 400

    /** Human-readable name (e.g. "Not Found", "Internal Server Error"). */
    def name: String = this match
        case HttpStatus.Custom(c) => c.toString
        case other =>
            val raw = other.toString
            // Insert space before each uppercase letter (except the first)
            val sb = new StringBuilder(raw.length + 4)
            @tailrec def loop(i: Int): Unit =
                if i < raw.length then
                    val c = raw.charAt(i)
                    if i > 0 && c.isUpper then sb.append(' ')
                    sb.append(c)
                    loop(i + 1)
            loop(0)
            sb.toString
end HttpStatus

object HttpStatus:

    /** The standard status for `code`, or `Absent` when no standard status has that code. A primitive `Int` match compiles to a
      * `lookupswitch`, so this does no `Integer` boxing (which a `Dict[Int, HttpStatus]` key lookup would) and needs no lookup table.
      */
    private def standard(code: Int): Maybe[HttpStatus] = code match
        case 100 => Present(Informational.Continue)
        case 101 => Present(Informational.SwitchingProtocols)
        case 102 => Present(Informational.Processing)
        case 103 => Present(Informational.EarlyHints)
        case 200 => Present(Success.OK)
        case 201 => Present(Success.Created)
        case 202 => Present(Success.Accepted)
        case 203 => Present(Success.NonAuthoritativeInfo)
        case 204 => Present(Success.NoContent)
        case 205 => Present(Success.ResetContent)
        case 206 => Present(Success.PartialContent)
        case 300 => Present(Redirect.MultipleChoices)
        case 301 => Present(Redirect.MovedPermanently)
        case 302 => Present(Redirect.Found)
        case 303 => Present(Redirect.SeeOther)
        case 304 => Present(Redirect.NotModified)
        case 305 => Present(Redirect.UseProxy)
        case 307 => Present(Redirect.TemporaryRedirect)
        case 308 => Present(Redirect.PermanentRedirect)
        case 400 => Present(ClientError.BadRequest)
        case 401 => Present(ClientError.Unauthorized)
        case 402 => Present(ClientError.PaymentRequired)
        case 403 => Present(ClientError.Forbidden)
        case 404 => Present(ClientError.NotFound)
        case 405 => Present(ClientError.MethodNotAllowed)
        case 406 => Present(ClientError.NotAcceptable)
        case 407 => Present(ClientError.ProxyAuthRequired)
        case 408 => Present(ClientError.RequestTimeout)
        case 409 => Present(ClientError.Conflict)
        case 410 => Present(ClientError.Gone)
        case 411 => Present(ClientError.LengthRequired)
        case 412 => Present(ClientError.PreconditionFailed)
        case 413 => Present(ClientError.PayloadTooLarge)
        case 414 => Present(ClientError.URITooLong)
        case 415 => Present(ClientError.UnsupportedMediaType)
        case 416 => Present(ClientError.RangeNotSatisfiable)
        case 417 => Present(ClientError.ExpectationFailed)
        case 418 => Present(ClientError.ImATeapot)
        case 421 => Present(ClientError.MisdirectedRequest)
        case 422 => Present(ClientError.UnprocessableEntity)
        case 423 => Present(ClientError.Locked)
        case 424 => Present(ClientError.FailedDependency)
        case 425 => Present(ClientError.TooEarly)
        case 426 => Present(ClientError.UpgradeRequired)
        case 428 => Present(ClientError.PreconditionRequired)
        case 429 => Present(ClientError.TooManyRequests)
        case 431 => Present(ClientError.RequestHeaderFieldsTooLarge)
        case 451 => Present(ClientError.UnavailableForLegalReasons)
        case 500 => Present(ServerError.InternalServerError)
        case 501 => Present(ServerError.NotImplemented)
        case 502 => Present(ServerError.BadGateway)
        case 503 => Present(ServerError.ServiceUnavailable)
        case 504 => Present(ServerError.GatewayTimeout)
        case 505 => Present(ServerError.HTTPVersionNotSupported)
        case 506 => Present(ServerError.VariantAlsoNegotiates)
        case 507 => Present(ServerError.InsufficientStorage)
        case 508 => Present(ServerError.LoopDetected)
        case 510 => Present(ServerError.NotExtended)
        case 511 => Present(ServerError.NetworkAuthRequired)
        case _   => Absent
    end standard

    /** Resolve an HTTP status code. Returns the known enum case if one exists, otherwise wraps in `Custom`. */
    def apply(code: Int): HttpStatus =
        require(code >= 100 && code <= 599, s"Invalid HTTP status code: $code")
        standard(code) match
            case Present(s) => s
            case Absent     => Custom(code)
    end apply

    def resolve(code: Int): Maybe[HttpStatus] =
        standard(code)

    /** Status code not covered by the standard enums. */
    final case class Custom(override val code: Int) extends HttpStatus(code)

    export ClientError.*
    export Informational.*
    export Redirect.*
    export ServerError.*
    export Success.*

    enum Informational(code: Int) extends HttpStatus(code):
        case Continue           extends Informational(100)
        case SwitchingProtocols extends Informational(101)
        case Processing         extends Informational(102)
        case EarlyHints         extends Informational(103)
    end Informational

    enum Success(code: Int) extends HttpStatus(code):
        case OK                   extends Success(200)
        case Created              extends Success(201)
        case Accepted             extends Success(202)
        case NonAuthoritativeInfo extends Success(203)
        case NoContent            extends Success(204)
        case ResetContent         extends Success(205)
        case PartialContent       extends Success(206)
    end Success

    enum Redirect(code: Int) extends HttpStatus(code):
        case MultipleChoices   extends Redirect(300)
        case MovedPermanently  extends Redirect(301)
        case Found             extends Redirect(302)
        case SeeOther          extends Redirect(303)
        case NotModified       extends Redirect(304)
        case UseProxy          extends Redirect(305)
        case TemporaryRedirect extends Redirect(307)
        case PermanentRedirect extends Redirect(308)
    end Redirect

    enum ClientError(code: Int) extends HttpStatus(code):
        case BadRequest                  extends ClientError(400)
        case Unauthorized                extends ClientError(401)
        case PaymentRequired             extends ClientError(402)
        case Forbidden                   extends ClientError(403)
        case NotFound                    extends ClientError(404)
        case MethodNotAllowed            extends ClientError(405)
        case NotAcceptable               extends ClientError(406)
        case ProxyAuthRequired           extends ClientError(407)
        case RequestTimeout              extends ClientError(408)
        case Conflict                    extends ClientError(409)
        case Gone                        extends ClientError(410)
        case LengthRequired              extends ClientError(411)
        case PreconditionFailed          extends ClientError(412)
        case PayloadTooLarge             extends ClientError(413)
        case URITooLong                  extends ClientError(414)
        case UnsupportedMediaType        extends ClientError(415)
        case RangeNotSatisfiable         extends ClientError(416)
        case ExpectationFailed           extends ClientError(417)
        case ImATeapot                   extends ClientError(418)
        case MisdirectedRequest          extends ClientError(421)
        case UnprocessableEntity         extends ClientError(422)
        case Locked                      extends ClientError(423)
        case FailedDependency            extends ClientError(424)
        case TooEarly                    extends ClientError(425)
        case UpgradeRequired             extends ClientError(426)
        case PreconditionRequired        extends ClientError(428)
        case TooManyRequests             extends ClientError(429)
        case RequestHeaderFieldsTooLarge extends ClientError(431)
        case UnavailableForLegalReasons  extends ClientError(451)
    end ClientError

    enum ServerError(code: Int) extends HttpStatus(code):
        case InternalServerError     extends ServerError(500)
        case NotImplemented          extends ServerError(501)
        case BadGateway              extends ServerError(502)
        case ServiceUnavailable      extends ServerError(503)
        case GatewayTimeout          extends ServerError(504)
        case HTTPVersionNotSupported extends ServerError(505)
        case VariantAlsoNegotiates   extends ServerError(506)
        case InsufficientStorage     extends ServerError(507)
        case LoopDetected            extends ServerError(508)
        case NotExtended             extends ServerError(510)
        case NetworkAuthRequired     extends ServerError(511)
    end ServerError

end HttpStatus
