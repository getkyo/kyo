package kyo

/** Opaque function type that attaches protocol-specific extra fields to outbound envelopes.
  *
  * Passed to `JsonRpcEndpoint.call`, `notify`, and `sendUnmatched` as the `extras` parameter.
  * The function receives the assigned request id and returns an optional `Structure.Value` map
  * that is merged into the outgoing envelope's `extras` field.
  *
  * Use the companion factories:
  *  - [[ExtrasEncoder.empty]]: no extras on every call.
  *  - [[ExtrasEncoder.const]]: attach the same extras value to every call.
  *  - [[ExtrasEncoder.apply]]: full control with a per-id function.
  *
  * @see [[JsonRpcEndpoint]]
  */
opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

object ExtrasEncoder:
    def apply(f: JsonRpcId => Maybe[Structure.Value] < Sync): ExtrasEncoder = f

    val empty: ExtrasEncoder = (_: JsonRpcId) => Absent

    def const(extras: Structure.Value): ExtrasEncoder =
        (_: JsonRpcId) => Present(extras)

    // opaque-type companion carve-out (FLOW Decision #30 (b))
    extension (self: ExtrasEncoder)
        def resolve(id: JsonRpcId)(using Frame): Maybe[Structure.Value] < Sync = self(id)
end ExtrasEncoder
