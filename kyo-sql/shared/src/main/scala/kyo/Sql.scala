package kyo

import kyo.SqlAst.*

/** Sql — top-level entry points for the redesigned DSL. All AST types and the DSL methods on `Term`/`Column`/`Query`/etc. live in
  * [[kyo.SqlAst]] (typically `import kyo.SqlAst.*` to bring them into scope).
  *
  * The `Sql.*` namespace deliberately exposes only what the user types to *start* a query / statement — `from`, `nested`, `lateral`,
  * `values`, `insert`, `update`, `delete`, `commonTable(s)(Recursive)`, `when`, `windowSpec`, plus the escape hatches `default` / `call` /
  * `raw`.
  */
object Sql:

    // --- FROM / source entry points ---

    /** Build a Table from a case class. Schema gives the SQL table name; alias is the user-chosen prefix. */
    inline def from[T]: FromBuilder[T] = new FromBuilder[T]

    /** Use an existing query as a source with new column aliases. */
    inline def nested[T]: NestedBuilder[T] = new NestedBuilder[T]

    /** Lateral subquery — its WHERE clause may reference columns from the surrounding FROM. */
    inline def lateral[T]: LateralBuilder[T] = new LateralBuilder[T]

    /** `VALUES (…), (…), …` constructor that produces a query of `T`. */
    inline def values[T]: ValuesBuilder[T] = new ValuesBuilder[T]

    // --- INSERT / UPDATE / DELETE entry points ---

    /** INSERT entry point — table name derived from `T`'s case-class label, column names from [[Fields]] (declaration order).
      *
      * Auto-key detection: if `T`'s first declared field is `Long`-typed, that field is treated as the auto-incrementing primary key. The
      * PG renderer auto-appends `RETURNING <pk>`; the MySQL driver reads `last_insert_id` from the OK packet. Future work: a `@PrimaryKey`
      * / `@AutoIncrement` annotation may provide explicit primary-key declaration; currently the first `Long` field heuristic is the only
      * detection mechanism.
      */
    transparent inline def insert[T](using f: Fields[T]) =
        val cols = SqlAst.internal.buildRowColumns[T]
        InsertBuilder[T, SqlAst.internal.RecordF[cols.type]](
            cols,
            kyo.internal.SqlMacros.tableName[T],
            kyo.internal.SqlMacros.columnNames[T],
            kyo.internal.SqlMacros.autoKey[T]
        )
    end insert

    /** UPDATE entry point. */
    transparent inline def update[T](using f: Fields[T]) =
        val cols = SqlAst.internal.buildRowColumns[T]
        UpdateBuilder[T, SqlAst.internal.RecordF[cols.type]](cols, kyo.internal.SqlMacros.tableName[T])

    /** DELETE entry point. */
    transparent inline def delete[T](using f: Fields[T]) =
        val cols = SqlAst.internal.buildRowColumns[T]
        DeleteBuilder[T, SqlAst.internal.RecordF[cols.type]](cols, kyo.internal.SqlMacros.tableName[T])

    // --- CTE (WITH) entry points ---

    /** Define a CTE — feed into [[commonTables]] to bind it. */
    inline def commonTable[B](inline name: String, inline query: Query[B]): CommonTable[B] = CommonTable(name, query)

    /** `WITH cte1, cte2, … body`. */
    inline def commonTables[B](inline tables: CommonTable[?]*)(inline body: Query[B]): Query[B] = With(Chunk.from(tables), body)

    /** `WITH RECURSIVE cte1, cte2, … body`. */
    inline def commonTablesRecursive[B](inline tables: CommonTable[?]*)(inline body: Query[B]): Query[B] =
        WithRecursive(Chunk.from(tables), body)

    // --- CASE WHEN entry point ---

    /** Open a CASE WHEN expression: `Sql.when(pred).to(value).when(pred2).to(value2).otherwise(default)`. */
    inline def when(inline predicate: Term[Boolean]): WhenBuilder = WhenBuilder(predicate)

    // --- Window spec entry point ---

    /** Window-spec builder entry point. Chain `.partitionBy(...).orderBy(...).frameRows(FrameBound.preceding(2), FrameBound.currentRow)`
      * then call a terminator (`.rowNumber`, `.rank`, etc.) for standalone window functions, or pass to `.over(spec)` from a column-based
      * aggregate / window function.
      */
    inline def windowSpec: WindowSpecBuilder = WindowSpecBuilder(Chunk.empty, Chunk.empty, Maybe.empty)

    // --- Escape hatches ---

    /** `DEFAULT` keyword for INSERT — `_.id := Sql.default` overrides a value with the column's default. */
    inline def default[A]: Term[A] = Default()

    /** Generic SQL function call (escape hatch). Args must be `Term[?]`s — wrap raw values via `Sql.literal(v)`. */
    inline def call[A](inline name: String, inline args: Term[?]*): Term[A] =
        FunctionCall(name, Chunk.from(args))

    /** Lifts a runtime value into a typed [[Term]] that can compose into Sql expressions.
      *
      * Used as the bridge between Scala values and SQL Terms: pass a `String`, `Int`, `BigDecimal`, `java.time.Instant`, `UUID`,
      * `InetAddress`, `java.time.Duration`, `Maybe[A]`, `Chunk[A]`, or any other type with a `SqlSchema[A]` given. The schema controls how
      * the value is bound at the wire layer (binary vs text format, per-backend OID dispatch).
      *
      * Common uses:
      *   - Function call arguments: `Sql.call[Int]("greatest", Sql.literal(10), Sql.literal(20))`
      *   - Comparison RHS: `users.age > Sql.literal(18)` (the implicit conversion does the same thing)
      *   - VALUES clause: `Sql.values(Sql.literal(1), Sql.literal(2))`
      *
      * For SQL NULL, pass `Maybe.Absent` (or any `Maybe[A]` whose absent value the user encodes as NULL).
      *
      * @param value
      *   the Scala value to lift; passed inline so static-SQL macros can capture its compile-time value when it's a literal
      * @param s
      *   the schema controlling encode/decode of `A`; implicit-resolved
      */
    inline def literal[A](inline value: A)(using s: SqlSchema[A]): Term[A] = Literal(value, s)

    /** Raw SQL fragment (escape hatch). */
    inline def raw[A](inline sql: String): Term[A] = RawSql(sql)

    /** Renders any DSL AST node — query, action (INSERT/UPDATE/DELETE), term, or raw fragment — into a [[RenderedSql]] (the SQL text and
      * the runtime bind values) for the given backend.
      *
      * Use for debugging, logging, migration scripts, or any caller that needs to inspect the SQL alongside its parameters without
      * executing it.
      */
    extension [A](self: SqlAst.SqlAst[A])
        def render(backend: SqlBackend)(using frame: Frame): RenderedSql =
            val r = kyo.internal.SqlRender.render(self, backend, frame)
            RenderedSql(r.sql, r.params)
    end extension

end Sql

/** Top-level `sql"..."` interpolator — builds a composable [[kyo.SqlAst.Fragment]] from a string interpolation. Interpolated arguments are
  * classified at compile time: subtypes of `Term[?]` are embedded inline (allowing column / sub-query references), and other types are
  * bound as parameters via their `SqlSchema[A]`.
  */
extension (sc: StringContext)
    inline def sql(inline args: Any*): SqlAst.Fragment[Any] =
        ${ kyo.internal.SqlFragmentMacro.sqlImpl('sc, 'args) }
end extension
