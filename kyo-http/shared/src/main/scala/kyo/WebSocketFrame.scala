package kyo

import kyo.*

/** A WebSocket message frame — either text (UTF-8) or binary.
  *
  * Protocol-level frames (ping, pong, close) are handled internally by backends and are not exposed to user code.
  */
enum WebSocketFrame derives CanEqual:
    case Text(data: String)
    case Binary(data: Span[Byte])
