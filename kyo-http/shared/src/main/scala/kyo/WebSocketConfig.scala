package kyo

import kyo.*

/** Configuration for a WebSocket endpoint or connection.
  *
  * @param bufferSize
  *   Channel capacity for inbound and outbound message queues. Controls backpressure — when a channel is full, the sender suspends.
  * @param maxFrameSize
  *   Maximum size in bytes of a single WebSocket frame. Frames exceeding this limit cause the connection to close.
  * @param autoPingInterval
  *   If set, the backend sends ping frames at this interval to keep the connection alive through proxies.
  * @param closeTimeout
  *   Maximum time to wait for a clean close handshake before forcibly closing the connection.
  * @param subprotocols
  *   WebSocket subprotocols to advertise during the handshake (e.g. "graphql-transport-ws").
  */
case class WebSocketConfig(
    bufferSize: Int = 32,
    maxFrameSize: Int = 65536,
    autoPingInterval: Maybe[Duration] = Absent,
    closeTimeout: Duration = 5.seconds,
    subprotocols: Seq[String] = Seq.empty
) derives CanEqual
