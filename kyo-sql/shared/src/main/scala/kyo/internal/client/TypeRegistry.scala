package kyo.internal.client

/** Per-connection map from PostgreSQL type name to OID.
  *
  * Populated at connection startup by querying `pg_type` for each name declared in [[kyo.SqlClientConfig.typeNames]]. Empty when no custom
  * types are configured, or on the MySQL backend which has no equivalent runtime type-name resolution path.
  *
  * This is a plain type alias — callers may use `Map[String, Int]` literals directly.
  */
type TypeRegistry = Map[String, Int]

object TypeRegistry:

    /** An empty registry — no custom types configured. */
    val empty: TypeRegistry = Map.empty

    /** Constructs a [[TypeRegistry]] from an existing map. */
    def apply(entries: Map[String, Int]): TypeRegistry = entries

    extension (self: TypeRegistry)

        /** Looks up `typeName`, returning [[kyo.Present]] with the OID or [[kyo.Absent]] if not registered. */
        def lookup(typeName: String): kyo.Maybe[Int] =
            self.get(typeName) match
                case scala.Some(oid) => kyo.Present(oid)
                case scala.None      => kyo.Absent
    end extension

end TypeRegistry
