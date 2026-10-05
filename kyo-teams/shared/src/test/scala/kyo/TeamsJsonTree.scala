package kyo

/** JSON text as a tree whose records are sorted by key, so two documents compare by content whatever their key order. */
object TeamsJsonTree:
    def apply(json: String)(using Frame): Structure.Value = sorted(Json.decode[Structure.Value](json).getOrThrow)

    private def sorted(value: Structure.Value): Structure.Value =
        value match
            case Structure.Value.Record(fields)  => Structure.Value.Record(fields.map((k, v) => (k, sorted(v))).sortBy(_._1))
            case Structure.Value.Sequence(elems) => Structure.Value.Sequence(elems.map(sorted))
            case other                           => other
end TeamsJsonTree
