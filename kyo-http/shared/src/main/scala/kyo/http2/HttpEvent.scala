package kyo.http2

import kyo.Absent
import kyo.Duration
import kyo.Maybe

/** Server-Sent Event (SSE) with typed data payload.
  *
  * Follows the SSE wire protocol. The `data` field is serialized/deserialized via `Schema`. Optional `event` names a logical event type,
  * `id` supports last-event-ID reconnection, and `retry` suggests a reconnection interval to the client.
  *
  * @tparam A
  *   The type of the event data payload
  *
  * @see
  *   [[kyo.http2.HttpClient.getSseJson]]
  * @see
  *   [[kyo.http2.HttpHandler.getSseJson]]
  * @see
  *   [[kyo.http2.HttpHandler.getSseText]]
  */
case class HttpEvent[+A](
    data: A,
    event: Maybe[String] = Absent,
    id: Maybe[String] = Absent,
    retry: Maybe[Duration] = Absent
) derives CanEqual
