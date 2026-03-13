package kyo

import kyo.*

/** Server-Sent Event (SSE) with typed data payload.
  *
  * Follows the SSE wire protocol. The `data` field is serialized/deserialized via `Json`. Optional `event` names a logical event type, `id`
  * supports last-event-ID reconnection, and `retry` suggests a reconnection interval to the client.
  */
case class HttpSseEvent[+A](
    data: A,
    event: Maybe[String] = Absent,
    id: Maybe[String] = Absent,
    retry: Maybe[Duration] = Absent
) derives CanEqual
