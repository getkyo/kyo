// flow-allow: PUBLIC opaque-type for the JsonRpcEndpoint.call/notify extras parameter
package kyo

opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

object ExtrasEncoder:
    def apply(f: JsonRpcId => Maybe[Structure.Value] < Sync): ExtrasEncoder = f

    val empty: ExtrasEncoder = (_: JsonRpcId) => Absent

    def const(extras: Structure.Value): ExtrasEncoder =
        (_: JsonRpcId) => Present(extras)

    // flow-allow: opaque-type companion carve-out (FLOW Decision #30 (b))
    extension (self: ExtrasEncoder)
        def resolve(id: JsonRpcId)(using Frame): Maybe[Structure.Value] < Sync = self(id)
end ExtrasEncoder
