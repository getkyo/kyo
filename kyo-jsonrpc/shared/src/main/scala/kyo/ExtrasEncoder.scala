package kyo

opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

object ExtrasEncoder:
    def apply(f: JsonRpcId => Maybe[Structure.Value] < Sync): ExtrasEncoder = f

    val empty: ExtrasEncoder = (_: JsonRpcId) => Absent

    def const(extras: Structure.Value): ExtrasEncoder =
        (_: JsonRpcId) => Present(extras)

    extension (self: ExtrasEncoder)
        def resolve(id: JsonRpcId)(using Frame): Maybe[Structure.Value] < Sync = self(id)
end ExtrasEncoder
