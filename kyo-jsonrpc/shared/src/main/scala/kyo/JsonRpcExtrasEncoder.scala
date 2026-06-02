package kyo

/** Opaque function type that attaches protocol-specific extra fields to outbound envelopes.
  *
  * Passed to `JsonRpcHandler.call`, `notify`, and `sendUnmatched` as the `extras` parameter.
  * The function receives the assigned request id and returns an optional `Structure.Value` map
  * that is merged into the outgoing envelope's `extras` field.
  *
  * Use the companion factories:
  *  - [[JsonRpcExtrasEncoder.empty]]: no extras on every call.
  *  - [[JsonRpcExtrasEncoder.const]]: attach the same extras value to every call.
  *  - [[JsonRpcExtrasEncoder.apply]]: full control with a per-id function.
  *
  * @see [[JsonRpcHandler]]
  */
opaque type JsonRpcExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

object JsonRpcExtrasEncoder:
    def apply(f: JsonRpcId => Maybe[Structure.Value] < Sync): JsonRpcExtrasEncoder = f

    val empty: JsonRpcExtrasEncoder = (_: JsonRpcId) => Absent

    def const(extras: Structure.Value): JsonRpcExtrasEncoder =
        (_: JsonRpcId) => Present(extras)

    extension (self: JsonRpcExtrasEncoder)
        def resolve(id: JsonRpcId)(using Frame): Maybe[Structure.Value] < Sync = self(id)
end JsonRpcExtrasEncoder
