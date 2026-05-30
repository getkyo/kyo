package kyo

/** Selects how the endpoint allocates `JsonRpcId` values for outbound requests.
  *
  * Three strategies are available:
  *  - [[IdStrategy.SequentialLong]]: monotonically increasing `Long` ids starting at 1.
  *  - [[IdStrategy.SequentialInt]]: monotonically increasing `Int` ids (wraps at `Int.MaxValue`).
  *  - [[IdStrategy.Custom]]: caller-supplied generator; use when interoperating with peers that
  *    require string ids or specific id formats.
  *
  * Set via [[JsonRpcEndpoint.Config.idStrategy]].
  *
  * @see [[JsonRpcEndpoint.Config]]
  */
enum IdStrategy derives CanEqual:
    case SequentialLong
    case SequentialInt
    case Custom(next: () => JsonRpcId < Sync)
end IdStrategy
