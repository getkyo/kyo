package kyo.internal

import kyo.*

/** The one canonical Web API request path plus the ambient bot-token `Local`. Every
  * public `Slack` Web API method delegates to `request`; the token is bound by
  * `connect`/`connectUnscoped` via `local.let` and read inside the handler's fiber
  * context. Slack signals errors as HTTP 200 + `{"ok":false}`, so the path uses
  * `failOnError = false` and branches on the decoded `ok`; a 429 maps to a typed
  * rate-limit failure; a transport `HttpException` is recovered into a typed
  * `SlackTransportException`. No `HttpException` leaks the row.
  */
private[kyo] object SlackWebApi:

    private[kyo] val local: Local[Maybe[SlackToken.Bot]] = Local.init(Absent)

    // Overridable base URL: production uses slack.com; tests point at a local server.
    private[kyo] val baseUrl: Local[String] = Local.init("https://slack.com/api")

    /** The permissive Slack status envelope: reads only `ok`/`error`; the permissive
      * decoder skips the sibling `Out` fields at the same JSON level. The typed `Out`
      * is decoded separately from the raw body string.
      */
    final private[kyo] case class SlackOk(ok: Boolean = false, error: Maybe[String] = Absent) derives Schema

    private[kyo] def request[In: Schema, Out: Schema](method: String, body: In)(using Frame): Out < (Async & Abort[SlackException]) =
        local.use {
            case Absent =>
                Abort.fail(
                    new SlackException.SlackHandshakeException("no Slack connection bound; call within Slack.connect/connectUnscoped")
                )
            case Present(token) =>
                val headers = Seq(
                    "Authorization" -> s"Bearer ${token.value}",
                    "Content-Type"  -> "application/json"
                )
                baseUrl.use { base =>
                    Abort.recover[HttpException] { (ex: HttpException) =>
                        Abort.fail(new SlackException.SlackTransportException(s"$method transport failure: ${ex.getMessage}", ex))
                    } {
                        // Send the JSON body as text and read the FULL response body string:
                        // kyo-http leaves `rawBody` Absent on a 2xx, so a JSON-typed response
                        // would lose the body needed to decode the typed `Out`. failOnError=false
                        // so an HTTP 200 + {"ok":false} and a 429 reach `mapResponse`.
                        HttpClient.postTextResponse(
                            s"$base/$method",
                            Json.encode(body),
                            headers = headers,
                            failOnError = false
                        ).map { response =>
                            val raw      = response.fields.body
                            val envelope = Json.decode[SlackOk](raw).getOrElse(SlackOk())
                            mapResponse[Out](
                                response.status,
                                response.headers.get("Retry-After"),
                                envelope,
                                Present(raw),
                                method
                            )
                        }
                    }
                }
        }

    /** Map a decoded Slack HTTP response to the typed `Out` or a typed failure.
      * Separated from the HTTP transport so tests can call it directly with real
      * values without an HTTP round-trip.
      */
    private[kyo] def mapResponse[Out: Schema](
        status: HttpStatus,
        retryAfterHeader: Maybe[String],
        envelope: SlackOk,
        rawBody: Maybe[String],
        method: String
    )(using Frame): Out < Abort[SlackException] =
        if status == HttpStatus.TooManyRequests then
            val retryAfter = retryAfterHeader.map(parseRetryAfter).getOrElse(1.second)
            Abort.fail(new SlackException.SlackRateLimitException(retryAfter, s"$method rate limited"))
        else if envelope.ok then decodeOut[Out](rawBody, method)
        else
            Abort.fail(new SlackException.SlackWebApiException(
                envelope.error.getOrElse("unknown_error"),
                s"$method failed: ${envelope.error.getOrElse("unknown_error")}"
            ))

    /** Decode the typed `Out` from the raw response body string. `rawBody` is
      * `Maybe[String]`; an absent body or a decode miss surfaces as
      * `SlackDecodeException`.
      */
    private[kyo] def decodeOut[Out: Schema](rawBody: Maybe[String], method: String)(using Frame): Out < Abort[SlackException] =
        rawBody match
            case Absent => Abort.fail(new SlackException.SlackDecodeException(s"$method response had no body to decode"))
            case Present(raw) =>
                Json.decode[Out](raw) match
                    case Result.Success(out) => out
                    case other =>
                        Abort.fail(new SlackException.SlackDecodeException(s"$method response did not decode to the expected type: $other"))

    private def parseRetryAfter(raw: String): Duration =
        raw.toIntOption match
            case Some(secs) => secs.seconds
            case None       => 1.second

    /** POST a block_actions message update to the interaction's captured
      * `response_url`. Routes the message through the same native wire body the Web
      * API uses (`blocks` as a JSON array, `thread_ts` snake_case), not a raw encode
      * of the public camelCase `SlackMessage`, so Slack accepts the update. Uses the
      * same transport-error recovery so no `HttpException` leaks; the response body is
      * not consumed. A malformed blocks string surfaces as a typed
      * `SlackDecodeException`.
      */
    private[kyo] def postResponseUrl(url: String, message: SlackMessage)(using Frame): Unit < (Async & Abort[SlackException]) =
        Slack.parseBlocks(message.blocksJson).map { blocks =>
            val body = Slack.PostMessageBody(message.channel, message.text, blocks, message.threadTs)
            Abort.recover[HttpException] { (ex: HttpException) =>
                Abort.fail(new SlackException.SlackTransportException(s"response_url POST failed: ${ex.getMessage}", ex))
            } {
                HttpClient.postJson[SlackOk](url, body).unit
            }
        }

end SlackWebApi
