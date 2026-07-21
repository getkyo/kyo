package kyo

/** Extension methods on [[SqlSchema]] that attach naming and table-name overrides.
  *
  * These transforms delegate to the private[kyo] helpers in [[SqlSchema]] companion, which update the `SqlSchema.State` wrapper carrying
  * the naming strategy, the table-name override, and the SQL-specific field renames.
  *
  * Usage:
  * {{{
  *   given SqlSchema[Country] = SqlSchema.derived[Country]
  *       .withNaming(NamingStrategy.snakeCase)
  *       .withTableName("countries")
  * }}}
  */
extension [A](self: SqlSchema[A])

    /** Returns a new [[SqlSchema]] with the given [[NamingStrategy]] attached.
      *
      * The strategy is stored on the [[SqlSchema.State]] wrapper. Phase 3 macros read it via [[SqlSchema.readNamingStrategy]] to convert
      * Scala field names to SQL column names at expansion time. Transforms compose: calling `.withNaming` after `.withTableName` preserves
      * the table-name override and vice versa.
      *
      * @param strategy
      *   the naming convention to attach (e.g. `NamingStrategy.snakeCase`)
      */
    transparent inline def withNaming(strategy: NamingStrategy): SqlSchema[A] =
        SqlSchema.applyNamingStrategy(self, strategy)

    /** Returns a new [[SqlSchema]] with the given SQL table name attached.
      *
      * The name is stored on the [[SqlSchema.State]] wrapper. Phase 3 macros read it via [[SqlSchema.readTableNameOverride]] and emit it
      * as the table name literal rather than deriving from the Scala type name. Transforms compose: calling `.withTableName` after
      * `.withNaming` preserves the naming strategy and vice versa.
      *
      * @param name
      *   the SQL table name to use (e.g. `"countries"`)
      */
    transparent inline def withTableName(inline name: String): SqlSchema[A] =
        SqlSchema.applyTableNameOverride(self, name)

    /** Returns a new [[SqlSchema]] with a single field renamed for SQL output.
      *
      * Appends `(from, to)` to the schema's `renamedFields` list on the [[SqlSchema.State]] wrapper. The rename is consulted at
      * `Column`-construction time by `resolveSqlName` (via [[kyo.internal.SqlNameResolver.columnName]]): the Scala field named `from` will
      * be emitted in rendered SQL as `to`. Composable: calling `.rename` multiple times accumulates all pairs in order.
      *
      * @param from
      *   the Scala field name to match
      * @param to
      *   the SQL column name to emit in its place
      */
    transparent inline def rename(inline from: String, inline to: String): SqlSchema[A] =
        SqlSchema.applyRenamedField(self, from, to)

    /** Reads the [[NamingStrategy]] attached to this schema. Returns [[Maybe.Absent]] when no strategy has been set. */
    private[kyo] def namingStrategy: Maybe[NamingStrategy] =
        SqlSchema.readNamingStrategy(self)

    /** Reads the SQL table name override attached to this schema. Returns [[Maybe.Absent]] when no override has been set. */
    private[kyo] def tableNameOverride: Maybe[String] =
        SqlSchema.readTableNameOverride(self)

end extension
