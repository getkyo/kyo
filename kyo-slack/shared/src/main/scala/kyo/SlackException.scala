package kyo

/** Typed error hierarchy for kyo-slack: a sealed base extending `KyoException`
  * with six final leaves, one per failure mode. Modeled on `HttpException`: a flat
  * base plus leaves (six leaves do not earn the intermediate subcategory layer
  * `HttpException`'s ~17 leaves do). The web-api and rate-limit leaves carry typed
  * fields (the Slack error code, the backoff), not a discriminator string.
  *
  * No leaf message renders a token: a message names the Slack error code and the
  * offending frame, never the `AppLevel`/`Bot` value.
  */
sealed abstract class SlackException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

object SlackException:

    /** Connect/handshake failure: `apps.connections.open` ok:false, a missing wss
      * url, or no ambient connection bound when a Web API method is called.
      */
    final class SlackHandshakeException(message: String, cause: String | Throwable = "")(using Frame)
        extends SlackException(message, cause)

    /** The socket dropped or a frame failed to send/receive at the transport. */
    final class SlackTransportException(message: String, cause: String | Throwable = "")(using Frame)
        extends SlackException(message, cause)

    /** A structural decode failure at the one structural-decode site (`Slack.custom`'s
      * `Out`); never raised by the best-effort receive-loop decode (that path yields
      * a typed `Unknown`).
      */
    final class SlackDecodeException(message: String, cause: String | Throwable = "")(using Frame)
        extends SlackException(message, cause)

    /** A Web API call returned `{"ok":false}`; `error` carries the Slack error code. */
    final class SlackWebApiException(val error: String, message: String, cause: String | Throwable = "")(using Frame)
        extends SlackException(message, cause)

    /** A Web API call was rate-limited (HTTP 429); `retryAfter` carries the parsed
      * `Retry-After` backoff.
      */
    final class SlackRateLimitException(val retryAfter: Duration, message: String, cause: String | Throwable = "")(using Frame)
        extends SlackException(message, cause)

    /** The socket was permanently disabled (`disconnect` reason `link_disabled`);
      * terminal under every reconnect policy.
      */
    final class SlackTerminalException(message: String, cause: String | Throwable = "")(using Frame)
        extends SlackException(message, cause)

end SlackException
