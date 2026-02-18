package kyo

/** HTTP response status code with category classification.
  *
  * A sealed hierarchy covering all standard HTTP status codes organized into `Informational` (1xx), `Success` (2xx), `Redirect` (3xx),
  * `ClientError` (4xx), and `ServerError` (5xx) enums, plus a `Custom` case for non-standard codes. Use the companion `apply` to resolve an
  * integer code to its known case or wrap it in `Custom`.
  *
  * @see
  *   [[kyo.HttpResponse]]
  * @see
  *   [[kyo.HttpRoute]]
  */
sealed abstract class HttpStatus(val code: Int) derives CanEqual:
    def isInformational: Boolean = code >= 100 && code < 200
    def isSuccess: Boolean       = code >= 200 && code < 300
    def isRedirect: Boolean      = code >= 300 && code < 400
    def isClientError: Boolean   = code >= 400 && code < 500
    def isServerError: Boolean   = code >= 500 && code < 600
    def isError: Boolean         = code >= 400
end HttpStatus

object HttpStatus:

    private val byCode: Map[Int, HttpStatus] =
        (Informational.values.iterator ++ Success.values.iterator ++
            Redirect.values.iterator ++ ClientError.values.iterator ++
            ServerError.values.iterator).map(s => s.code -> s).toMap

    /** Resolve an HTTP status code. Returns the known enum case if one exists, otherwise wraps in `Custom`. */
    def apply(code: Int): HttpStatus =
        require(code >= 100 && code <= 599, s"Invalid HTTP status code: $code")
        byCode.getOrElse(code, Custom(code))

    def resolve(code: Int): Maybe[HttpStatus] =
        Maybe.fromOption(byCode.get(code))

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
