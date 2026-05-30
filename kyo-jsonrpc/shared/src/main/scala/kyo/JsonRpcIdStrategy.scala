package kyo

/** Selects how the endpoint allocates [[JsonRpcId]] values for outbound requests.
  *
  * Three strategies are available:
  *  - [[JsonRpcIdStrategy.SequentialLong]]: monotonically increasing `Long` ids starting at 1.
  *  - [[JsonRpcIdStrategy.SequentialInt]]: monotonically increasing `Int` ids (wraps at `Int.MaxValue`).
  *  - [[JsonRpcIdStrategy.Custom]]: caller-supplied generator; use when interoperating with peers that
  *    require string ids or specific id formats.
  *
  * Set via [[JsonRpcHandler.Config.idStrategy]].
  *
  * @see [[JsonRpcHandler.Config]]
  */
enum JsonRpcIdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
end JsonRpcIdStrategy
