package kyo

/** Root of the typed error hierarchy: the `Abort[WhatsAppError]` failure channel of
  * every client call. A sealed abstract class extending `KyoException` so a failure carries
  * a human-readable, source-located message and an optional cause, mirroring the kyo-http
  * `HttpException` precedent and kyo-slack's `SlackException` (a sealed abstract root,
  * subcategory abstract classes by failure mode, leaf-per-failure-mode with typed fields).
  * `Cloud` carries the full decoded Graph error envelope and is the total fallback for any
  * error code the typed leaves do not name, so an unmapped code still surfaces every field.
  * `Transport` wraps a non-Graph `HttpException` (connect/timeout) so a transport failure is
  * a typed value, never a silent drop.
  *
  * `KyoException` is `private[kyo]`; this hierarchy lives in package `kyo`, so the base
  * constructor is reachable. Each leaf and subcategory threads `(using Frame)` so the
  * constructing call site is captured for the error message.
  */
sealed abstract class WhatsAppError(message: => String = "", cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object WhatsAppError:

    /** The decoded Graph error envelope verbatim; also embedded in a failed
      * `WhatsAppNotification.StatusUpdate.errors`. Carries every documented field for diagnosis
      * and is the total fallback leaf for any code `Codec.mapError` does not name. When an error
      * code is not claimed by a typed leaf, this carries the raw code, subcode, type string,
      * message, details string, and Facebook trace id. Safe to pattern-match on the `code` field
      * for additional per-code handling.
      *
      * It is a `case class derives CanEqual` that also extends `KyoException`. The `derives
      * CanEqual` compares the declared case fields (`code`, `subcode`, `errorType`, `message`,
      * `details`, `fbtraceId`), all comparable, and ignores the inherited `Frame`. No `Schema`
      * is derived over this type: the inbound `WhatsAppNotification.StatusUpdate.errors` field
      * (a `Chunk[Cloud]`) only `derives CanEqual`, and the wire-layer error DTO
      * (`internal.whatsapp.Wire.ErrorDto`) is the `Schema`-bearing type, mapped to `Cloud`
      * manually in the codec. So extending `KyoException` does not affect any `Schema`.
      */
    final case class Cloud(
        code: Int,
        subcode: Maybe[Int] = Absent,
        errorType: String,
        message: String,
        details: Maybe[String] = Absent,
        fbtraceId: Maybe[String] = Absent
    )(using Frame) extends WhatsAppError(message) derives CanEqual

    /** Authentication failures (codes 0/190/10/200/131005). */
    sealed abstract class AuthError(message: String)(using Frame) extends WhatsAppError(message)
    object AuthError:
        final case class TokenExpired()(using Frame) extends AuthError("the access token has expired")
        final case class AccessDenied()(using Frame) extends AuthError("permission denied")
        given CanEqual[AuthError, AuthError] = CanEqual.derived
    end AuthError

    /** Rate limiting (codes 4/130429/80007). */
    final case class RateLimited(code: Int, message: String, scope: RateLimited.Scope)(using Frame)
        extends WhatsAppError(message) derives CanEqual
    object RateLimited:
        sealed trait Scope derives CanEqual
        case object PhoneNumber extends Scope
        case object Waba        extends Scope
        case object Throughput  extends Scope
    end RateLimited

    /** Recipient delivery failures. `Undeliverable` covers all send-failure codes
      * including the case where the recipient does not have a WhatsApp account (the
      * Cloud API does not provide a distinct error code for that sub-case, mapping it
      * to the same undeliverable family). `SenderEqualsRecipient` is the distinct code
      * for a self-send attempt.
      */
    sealed abstract class RecipientError(message: String)(using Frame) extends WhatsAppError(message)
    object RecipientError:
        final case class Undeliverable()(using Frame)         extends RecipientError("message undeliverable")
        final case class SenderEqualsRecipient()(using Frame) extends RecipientError("recipient cannot be the sender")
        given CanEqual[RecipientError, RecipientError] = CanEqual.derived
    end RecipientError

    /** Free-form send after the 24h window closed (code 131047): send a template. */
    final case class WindowClosed()(using Frame)
        extends WhatsAppError("more than 24 hours have passed since the recipient last replied")
    object WindowClosed:
        given CanEqual[WindowClosed, WindowClosed] = CanEqual.derived

    /** Template failures (codes 132000/132001/132005/132007/132012/132015). */
    sealed abstract class TemplateError(message: String)(using Frame) extends WhatsAppError(message)
    object TemplateError:
        final case class DoesNotExist()(using Frame)        extends TemplateError("template does not exist")
        final case class ParamCountMismatch()(using Frame)  extends TemplateError("template parameter count mismatch")
        final case class ParamFormatMismatch()(using Frame) extends TemplateError("template parameter format mismatch")
        final case class ContentPolicy()(using Frame)       extends TemplateError("template content policy violation")
        final case class Paused()(using Frame)              extends TemplateError("template is paused")
        final case class TextTooLong()(using Frame)         extends TemplateError("template text too long")
        given CanEqual[TemplateError, TemplateError] = CanEqual.derived
    end TemplateError

    /** Media failures (codes 131052/131053). */
    sealed abstract class MediaError(message: String)(using Frame) extends WhatsAppError(message)
    object MediaError:
        final case class DownloadFailed()(using Frame) extends MediaError("unable to download the media")
        final case class UploadFailed()(using Frame)   extends MediaError("unable to upload the media")
        given CanEqual[MediaError, MediaError] = CanEqual.derived
    end MediaError

    /** Invalid request parameter (codes 100/131008/131009/135000). */
    final case class InvalidParameter(code: Int, message: String)(using Frame)
        extends WhatsAppError(message) derives CanEqual

    /** Temporary Meta-side failure, retryable (codes 131000/131016). */
    final case class ServiceUnavailable(code: Int, message: String)(using Frame)
        extends WhatsAppError(message) derives CanEqual

    /** A non-Graph transport failure (connection refused, connection closed, timeout)
      * wrapping the underlying cause; surfaced as a typed value, never dropped. The
      * cause is an `HttpException` for protocol-level failures or a raw `java.io.IOException`
      * when the HTTP layer surfaces a connection close as an untyped throwable.
      */
    final case class Transport(cause: Throwable)(using Frame)
        extends WhatsAppError("transport failure", cause)

    /** A structurally-unparseable response or webhook envelope. Distinct from an unknown
      * type, which decodes to a degenerate case (Unknown/Unsupported/Other), not a failure.
      * The `cause` case field carries the human-readable parse detail; it is independent of
      * the inherited `KyoException.getCause()`, which stays `null` for this leaf.
      */
    final case class DecodeError(cause: String)(using Frame) extends WhatsAppError(cause) derives CanEqual

    /** Webhook signature-verification outcome (the failure side of `verifySignature`). */
    sealed abstract class SignatureError(message: String)(using Frame) extends WhatsAppError(message)
    object SignatureError:
        final case class Missing()(using Frame)   extends SignatureError("X-Hub-Signature-256 header is missing")
        final case class Malformed()(using Frame) extends SignatureError("X-Hub-Signature-256 header is malformed")
        final case class Mismatch()(using Frame)  extends SignatureError("X-Hub-Signature-256 does not match the body")
        given CanEqual[SignatureError, SignatureError] = CanEqual.derived
    end SignatureError

    given CanEqual[WhatsAppError, WhatsAppError] = CanEqual.derived

end WhatsAppError
