package kyo

import kyo.internal.whatsapp.Codec as WhatsAppCodec
import kyo.internal.whatsapp.Hmac

/** Inbound webhook namespace for the WhatsApp Cloud API notification surface. Covers the
  * GET verification handshake Meta requires to register a webhook endpoint, HMAC-SHA256
  * signature verification of incoming POST payloads, typed decoding of the POST body into
  * `WhatsAppNotification` values, and a fused POST handler for the common case.
  *
  * Operations:
  *   - `verificationHandler` builds a GET `HttpHandler` for the hub.challenge echo. Returns
  *     200 with the challenge on a token match, 403 otherwise.
  *   - `verifySignature` verifies the `X-Hub-Signature-256` header against the raw body bytes
  *     using a pure cross-platform HMAC-SHA256 implementation. Returns `Result.unit` on a
  *     match; returns a typed `WhatsAppError.SignatureError` on missing, malformed, or
  *     mismatched signatures. Never throws.
  *   - `decode` parses the verified raw body into a `Chunk[WhatsAppNotification]`, aborting
  *     with `WhatsAppError.DecodeError` only on a structurally unparseable envelope. Unknown
  *     message types and statuses decode to degenerate cases, not aborts.
  *   - `handler` fuses verify, decode, and a per-notification callback into a POST
  *     `HttpHandler`. Responds 200 even when decode fails (to prevent Meta retries on a
  *     structurally broken payload), logging the decode error instead.
  */
object WhatsAppWebhook:

    /** GET handshake: echoes hub.challenge as a text body with 200 on a token match, 403
      * otherwise. Uses a typed text-body response route so the challenge string is delivered
      * as the response body.
      */
    def verificationHandler(verifyToken: String)(using
        Frame
    ): HttpHandler[
        "mode" ~ Maybe[String] & "token" ~ Maybe[String] & "challenge" ~ Maybe[String],
        "body" ~ String,
        WhatsAppError
    ] =
        HttpRoute.getRaw("")
            .request(_.queryOpt[String]("mode", wireName = "hub.mode"))
            .request(_.queryOpt[String]("token", wireName = "hub.verify_token"))
            .request(_.queryOpt[String]("challenge", wireName = "hub.challenge"))
            .response(_.bodyText)
            .handler[WhatsAppError] { req =>
                val mode      = req.fields.mode
                val token     = req.fields.token
                val challenge = req.fields.challenge
                (mode, token, challenge) match
                    case (Present("subscribe"), Present(t), Present(c)) if t == verifyToken =>
                        HttpResponse.ok(c)
                    case _ =>
                        HttpResponse.halt(HttpResponse.forbidden)
                end match
            }

    /** X-Hub-Signature-256 verification. Total and pure: Success(()) on a matching
      * lowercase-hex HMAC-SHA256 over the raw body, Failure on missing/malformed/mismatch.
      * Cross-platform pure-Scala HMAC; never throws.
      */
    def verifySignature(
        appSecret: String,
        signatureHeader: Maybe[String],
        body: Span[Byte]
    )(using Frame): Result[WhatsAppError.SignatureError, Unit] =
        signatureHeader match
            case Absent => Result.fail(WhatsAppError.SignatureError.Missing())
            case Present(header) =>
                if !header.startsWith("sha256=") then Result.fail(WhatsAppError.SignatureError.Malformed())
                else
                    val hex = header.substring("sha256=".length)
                    if hex.isEmpty || hex.length % 2 != 0 || !hex.forall(isHexChar) then
                        Result.fail(WhatsAppError.SignatureError.Malformed())
                    else
                        val provided = decodeHex(hex)
                        val computed = Hmac.hmacSha256(appSecret.getBytes("UTF-8"), body.toArray)
                        if Hmac.constantTimeEquals(provided, computed) then Result.unit
                        else Result.fail(WhatsAppError.SignatureError.Mismatch())
                    end if

    /** Typed decode of the raw verified body into one WhatsAppNotification per
      * entry[].changes[]. An unknown type/status/field decodes degenerate, never an Abort; a
      * structurally-unparseable envelope aborts with DecodeError.
      */
    def decode(body: Span[Byte])(using Frame): Chunk[WhatsAppNotification] < Abort[WhatsAppError.DecodeError] =
        Abort.get(WhatsAppCodec.decodeNotifications(body))

    /** Fused POST handler: reads bodyBinary (byte-exact for HMAC), verifies the signature
      * (403 on failure), decodes, invokes `f` per WhatsAppNotification, returns 200. An
      * unparseable change is logged-and-skipped so a structurally-broken-but-delivered POST is
      * acked, not retried by Meta.
      */
    def handler(appSecret: String)(using
        Frame
    )(f: WhatsAppNotification => Unit < (Async & Abort[WhatsAppError])): HttpHandler["body" ~ Span[Byte], Any, WhatsAppError] =
        HttpRoute.postRaw("").request(_.bodyBinary).handler[WhatsAppError] { req =>
            val body = req.fields.body
            verifySignature(appSecret, req.headers.get("X-Hub-Signature-256"), body) match
                case Result.Failure(_) => HttpResponse.halt(HttpResponse.forbidden)
                case _ =>
                    Abort.run[WhatsAppError.DecodeError](decode(body)).map {
                        case Result.Success(notifications) =>
                            Kyo.foreach(notifications)(f).andThen(HttpResponse.ok)
                        case Result.Failure(e) =>
                            Log.warn(s"webhook decode skipped an unparseable change: ${e.cause}").andThen(HttpResponse.ok)
                        case Result.Panic(ex) =>
                            Abort.panic(ex)
                    }
            end match
        }

    private def isHexChar(c: Char): Boolean = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

    private def decodeHex(hex: String): Array[Byte] =
        val out = new Array[Byte](hex.length / 2)
        @scala.annotation.tailrec
        def loop(i: Int): Unit =
            if i < out.length then
                out(i) = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16).toByte
                loop(i + 1)
        loop(0)
        out
    end decodeHex

end WhatsAppWebhook
