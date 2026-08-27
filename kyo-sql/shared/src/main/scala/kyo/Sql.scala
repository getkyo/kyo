package kyo

import kyo.Record.~
import kyo.db.Idiom
import scala.annotation.implicitNotFound
import scala.annotation.publicInBinary
import scala.annotation.targetName
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.summonFrom
import scala.compiletime.summonInline
import scala.deriving.Mirror
import scala.util.NotGiven

// --- Sql, root of the AST hierarchy ---

/** Root of the SQL AST hierarchy. Every node, expressions, queries, mutations, raw fragments, is a `Sql[A]`. */
sealed trait Sql[A]:

    /** Renders this AST node into a [[Sql.Rendered]] in `dialect`, targeting `version`.
      *
      * `version` is the server the SQL is meant for, which matters wherever a flavor gained a construct in a specific release: MySQL
      * renders `INTERSECT` from 8.0.31 onwards and `LATERAL` from 8.0.14. Leaving it `Absent` targets the dialect's capability floor, the
      * oldest server the dialect renders for, so the result is SQL any supported server of that flavor accepts. Asking for a construct
      * the target cannot express fails with [[kyo.SqlUnsupportedDialectFeatureException]] rather than emitting SQL the server would
      * reject.
      *
      * Use this for debugging, logging, or migration scripts without a live client. When a client is already in hand, prefer
      * [[SqlClient.render]], which supplies both the client's own dialect and the version it captured at handshake.
      */
    final def render(dialect: Idiom, version: Maybe[Idiom.ServerVersion] = Absent)(using frame: Frame): Sql.Rendered =
        dialect.render(this, version, frame)
end Sql

/** SQL Abstract Syntax Tree and DSL entry points for the Sql DSL.
  *
  * Holds every AST node, every typeclass, and every DSL method that constructs an AST node, alongside the top-level entry points a query
  * or statement starts from. The entry points deliberately expose only what the user types to *start* something, `from`, `nested`,
  * `lateral`, `values`, `insert`, `update`, `delete`, `commonTable(s)(Recursive)`, `when`, `windowSpec`, plus the escape hatches
  * `default` / `call` / `raw`. DSL builders live in the companion of the AST type they produce (e.g. [[WindowSpec.Builder]],
  * [[Insert.Builder]], [[Update.Builder]]).
  *
  * Import `kyo.Sql.*` to bring every type and DSL method, on `Term` / `Column` / `Query` / etc. as well as the entry points above, into
  * scope.
  *
  * Design, no `Kind` parameter; aggregate / window distinctions are encoded in the type structure:
  *
  *   - [[Term]], any expression usable as a scalar.
  *   - [[Column]], table column reference. Aggregate methods (`.sum`, `.count`, `.avg`, …) produce a [[WindowAggregate]] (NOT a `Term`),
  *     usable only via `.over(spec)`. This blocks `where(col.count > 5)` and `col.count.sum` at compile time.
  *   - [[GroupTerm]], post-groupBy ungrouped column. Only aggregate methods.
  *   - [[GroupedColumn]], post-groupBy grouped column. Has the `Term[V]` surface AND the aggregate methods.
  *   - Whole-table aggregates without GROUP BY use [[From.sum]] / [[From.count]] / etc., returning a `Query` (a `Term` via scalar subquery).
  *
  * An aggregate's result type is never assumed to be its operand's. `SUM` over an `Int` column is a `Long`, `AVG` over any exact type is a
  * `BigDecimal`, and a whole-table aggregate is a `Maybe` because the server answers an empty input with one row holding no value.
  * [[SqlNumeric]] carries the widths and [[AsMaybe]] the possible absence; the same pair types `/`, whose quotient is fractional rather
  * than integral.
  */
object Sql:

    // --- FROM / source entry points ---

    /** Build a Table from a case class, aliased by the decapitalized type name: `Sql.from[Invoice]` reads as `r.invoice.total`.
      *
      * An explicit alias overrides the derived one (`Sql.from[Invoice]("i")` reads as `r.i.total`), and a table name beside it
      * overrides the `T`-derived table (`Sql.from[Invoice]("i", "invoice_ledger")`). A self-join needs two distinct record keys, so at
      * least one side of it aliases explicitly.
      */
    transparent inline def from[T](using m: scala.deriving.Mirror.ProductOf[T], f: Fields[T]) =
        kyo.internal.SqlMacros.validateDerivedAlias[T]
        type N0 = SqlNaming.Decapitalize[m.MirroredLabel] & Singleton
        type F0 = N0 ~ Record[f.MapNamed[Column]]
        Table[T, F0](
            kyo.internal.SqlAstInternal.buildColumns[T, N0](scala.compiletime.constValue[N0]).asInstanceOf[Record[F0]],
            scala.compiletime.constValue[N0],
            kyo.internal.SqlMacros.tableName[T],
            kyo.internal.SqlMacros.columnNames[T]
        )
    end from

    /** Use an existing query as a source with new column aliases. */
    inline def nested[T]: Nested.Builder[T] = new Nested.Builder[T]

    /** Lateral subquery, its WHERE clause may reference columns from the surrounding FROM. */
    inline def lateral[T]: Lateral.Builder[T] = new Lateral.Builder[T]

    /** `VALUES (…), (…), …` constructor that produces a query of `T`. */
    inline def values[T]: ValuesFrom.Builder[T] = new ValuesFrom.Builder[T]

    // --- INSERT / UPDATE / DELETE entry points ---

    /** INSERT entry point, table name derived from `T`'s case-class label, column names from [[Fields]] (declaration order).
      *
      * Auto-key detection: if `T`'s first declared field is `Long`-typed, that field is treated as the auto-incrementing primary key. The
      * PG renderer auto-appends `RETURNING <pk>`; the MySQL driver reads `last_insert_id` from the OK packet.
      *
      * ==Inserting into a generated-key column==
      *
      * `values(row)` sends every column, the detected auto-key included, with whatever the row carries. Detection is a rule about the Scala
      * type and says nothing about the DDL: `case class User(id: Long, name: String)` is the same shape whether the table's key is
      * `BIGSERIAL` or a plain `BIGINT PRIMARY KEY` the caller fills itself, and omitting the column would break the second table. So asking
      * the server to assign the key is said at the call site, either way round:
      *
      *   - `Sql.insert[User].values(user).overriding(_.id := Sql.default)` keeps the row and replaces that one cell with `DEFAULT`.
      *   - `Sql.insert[User].partialValues(_.name := "amy")` names the columns to send and omits the rest.
      *
      * Sending an explicit value into a generated-key column is accepted rather than rejected, and on PostgreSQL an explicit `0` into a
      * `BIGSERIAL` lands as `0` without advancing the sequence, so the key reported back is the caller's own `0` and the next such insert
      * collides with it. That is the failure the two spellings above avoid.
      */
    transparent inline def insert[T](using f: Fields[T]) =
        val cols = kyo.internal.SqlAstInternal.buildRowColumns[T]
        Insert.Builder[T, Sql.RecordF[cols.type]](
            cols,
            kyo.internal.SqlMacros.tableName[T],
            kyo.internal.SqlMacros.columnNames[T],
            kyo.internal.SqlMacros.autoKey[T]
        )
    end insert

    /** INSERT into an explicitly named table, overriding the `T`-derived default. The name is a literal, folded directly into the static
      * render. Everything else (column names, auto-key detection) is unchanged from the derived-name entry point above. Its body is a copy
      * rather than a delegation because two `transparent inline` overloads cannot delegate (the inferred return types would cycle).
      */
    transparent inline def insert[T](inline tableName: String)(using f: Fields[T]) =
        val cols = kyo.internal.SqlAstInternal.buildRowColumns[T]
        Insert.Builder[T, Sql.RecordF[cols.type]](
            cols,
            tableName,
            kyo.internal.SqlMacros.columnNames[T],
            kyo.internal.SqlMacros.autoKey[T]
        )
    end insert

    /** UPDATE entry point, table name derived from `T`'s case-class label. */
    transparent inline def update[T](using f: Fields[T]) =
        val cols = kyo.internal.SqlAstInternal.buildRowColumns[T]
        Update.Builder[T, Sql.RecordF[cols.type]](cols, kyo.internal.SqlMacros.tableName[T], Chunk.empty)

    /** UPDATE into an explicitly named table, overriding the `T`-derived default (a copy, not a delegation: transparent inline overloads
      * cannot delegate).
      */
    transparent inline def update[T](inline tableName: String)(using f: Fields[T]) =
        val cols = kyo.internal.SqlAstInternal.buildRowColumns[T]
        Update.Builder[T, Sql.RecordF[cols.type]](cols, tableName, Chunk.empty)

    /** DELETE entry point, table name derived from `T`'s case-class label. */
    transparent inline def delete[T](using f: Fields[T]) =
        val cols = kyo.internal.SqlAstInternal.buildRowColumns[T]
        Delete.Builder[T, Sql.RecordF[cols.type]](cols, kyo.internal.SqlMacros.tableName[T])

    /** DELETE from an explicitly named table, overriding the `T`-derived default (a copy, not a delegation: transparent inline overloads
      * cannot delegate).
      */
    transparent inline def delete[T](inline tableName: String)(using f: Fields[T]) =
        val cols = kyo.internal.SqlAstInternal.buildRowColumns[T]
        Delete.Builder[T, Sql.RecordF[cols.type]](cols, tableName)

    // --- CTE (WITH) entry points ---

    /** Define a CTE, feed into [[commonTables]] to bind it. */
    inline def commonTable[B](inline name: String, inline query: Query[B]): CommonTable[B] = CommonTable(name, query)

    /** `WITH cte1, cte2, … body`. */
    inline def commonTables[B](inline tables: CommonTable[?]*)(inline body: Query[B]): Query[B] = With(Chunk.from(tables), body)

    /** `WITH RECURSIVE cte1, cte2, … body`. */
    inline def commonTablesRecursive[B](inline tables: CommonTable[?]*)(inline body: Query[B]): Query[B] =
        WithRecursive(Chunk.from(tables), body)

    // --- CASE WHEN entry point ---

    /** Open a CASE WHEN expression: `Sql.when(pred).to(value).when(pred2).to(value2).otherwise(default)`. */
    inline def when(inline predicate: Term[Boolean]): Case.WhenBuilder = Case.WhenBuilder(predicate)

    // --- Window spec entry point ---

    /** Window-spec builder entry point. Chain `.partitionBy(...).orderBy(...).frameRows(FrameBound.preceding(2), FrameBound.currentRow)`
      * then call a terminator (`.rowNumber`, `.rank`, etc.) for standalone window functions, or pass to `.over(spec)` from a column-based
      * aggregate / window function.
      */
    inline def windowSpec: WindowSpec.Builder = WindowSpec.Builder(Chunk.empty, Chunk.empty, Maybe.empty)

    // --- Escape hatches ---

    /** The column's own declared default, the `DEFAULT` keyword.
      *
      * Assignable to a column and nothing else: `_.id := Sql.default` in [[Sql.Insert.overriding]], `partialValues`, `update`'s `set`, or a
      * conflict-update clause. It is a [[Sql.SetValue]] rather than a [[Sql.Term]] because no server accepts `DEFAULT` in a predicate, a
      * projection, or a function argument, so those positions do not take it and the mistake is a compile error.
      *
      * The use it exists for is a generated key: `Sql.insert[User].values(user).overriding(_.id := Sql.default)` sends `DEFAULT` in the key's
      * cell, so the server assigns the next value and reports it back.
      */
    inline def default[A]: SetValue.Default[A] = SetValue.Default()

    /** Generic SQL function call (escape hatch). Args must be `Term[?]`s, wrap raw values via `Sql.literal(v)`. */
    inline def call[A](inline name: String, inline args: Term[?]*): Term[A] =
        FunctionCall(name, Chunk.from(args))

    /** Lifts a runtime value into a typed [[Term]] that can compose into Sql expressions.
      *
      * Used as the bridge between Scala values and SQL Terms: pass a `String`, `Int`, `BigDecimal`, `java.time.Instant`, `UUID`,
      * `java.time.Duration`, `Maybe[A]`, `Chunk[Int]`, or any other single-column type with a `SqlSchema.Column[A]` given. The evidence's
      * schema controls how the value is bound at the wire layer (binary vs text format, per-backend OID dispatch).
      *
      * Common uses:
      *   - Function call arguments: `Sql.call[Int]("greatest", Sql.literal(10), Sql.literal(20))`
      *   - Comparison RHS: `users.age > Sql.literal(18)` (the implicit conversion does the same thing)
      *
      * A `VALUES` source is not one of them: [[Sql.values]] takes rows of `T` and decomposes them itself.
      *
      * To lift an absent value, pass `Maybe.Absent`. The backend spells absence on the wire; nothing here names it.
      *
      * @param value
      *   the Scala value to lift; passed inline so static-SQL macros can capture its compile-time value when it's a literal
      * @param c
      *   the single-column SQL evidence for `A`; its carried schema controls encode/decode; implicit-resolved
      */
    inline def literal[A](inline value: A)(using c: SqlSchema.Column[A]): Literal[A] = lit(value, c)

    /** A single-column SQL codec that stores `T` as a JSON document (`jsonb` on PostgreSQL, `JSON` on MySQL), with the document text
      * produced and consumed through the type's [[Schema]].
      *
      * The primary JSON column: `given SqlSchema.Column[Preferences] = Sql.jsonColumn` is all a `derives Schema` type needs. Use it for a
      * type whose shape is richer than one SQL column can express, or where querying the parts individually is not needed; sum types with
      * data-carrying variants are stored this way. A document that fails to decode is surfaced as a [[SqlDecodeJsonException]].
      */
    def jsonColumn[T](using frame: Frame, schema: Schema[T]): SqlSchema.Column[T] =
        jsonColumn[T](v => Json.encode(v))(text => Json.decode[T](text).getOrThrow)

    /** A single-column JSON codec over explicit document functions, for a JSON library other than kyo-schema.
      *
      * `encode` and `decode` supply the document text; the wire behavior is exactly [[jsonColumn]]'s, including the
      * [[SqlDecodeJsonException]] surfacing of a `decode` failure.
      */
    def jsonColumn[T](encode: T => String)(decode: String => T): SqlSchema.Column[T] =
        SqlSchema.of[T](
            write = (v, w) => w.json(encode(v)),
            read = r =>
                val text = r.nextJson()
                try decode(text)
                catch
                    case ex if scala.util.control.NonFatal(ex) =>
                        throw SqlDecodeJsonException(String.valueOf(ex.getMessage).take(100), ex)(using r.frame)
                end try
        )

    /** A single-column SQL codec that stores a singleton-variant sum type `E` as its variant label in a `TEXT` column.
      *
      * Every variant must be a case object / no-arg enum case; the variant name IS the stored label. A label read back that names no variant
      * aborts with [[SqlDecodeSumTypeUnknownLabelException]]. For a sum type with data-carrying variants, use [[jsonColumn]] instead.
      */
    inline def enumText[E](using m: Mirror.SumOf[E]): SqlSchema.Column[E] =
        enumTextImpl[E](
            Chunk.from(enumTextLabels[m.MirroredElemLabels]),
            Chunk.from(enumTextSingletons[m.MirroredElemTypes]),
            m
        )

    /** Raw SQL fragment (escape hatch). */
    inline def raw[A](inline sql: String): Term[A] = RawSql(sql)

    /** Public projection of the SQL renderer's output: the statement text per SQL flavor, and the runtime bind values that go with it.
      *
      * Produced from any [[Sql]] node ([[Sql.Query]], [[Sql.Action]], [[Sql.Term]], [[Sql.Fragment]]) via
      * `ast.render(dialect, version)`, or via [[SqlClient.render]] when a client is in hand. Used for logging, debugging, migration
      * scripts, or any caller that needs to inspect the rendered SQL alongside its parameters without executing it.
      *
      * Every render produces this shape, whichever number of flavors it covers: `ast.render` and [[SqlClient.render]] target one dialect
      * and fill a single entry, while `.runStatic` renders at compile time for every backend on the classpath and fills one entry each.
      * Read a known dialect's text with [[sqlFor]], or a single-dialect render's with [[onlySql]].
      *
      * The bind values sit beside the map rather than inside it because they do not vary by flavor: two dialects disagree on placeholder
      * syntax (`$1` against `?`) and identifier quoting, never on which values a statement binds or in what order.
      *
      * @param perDialect
      *   the rendering produced for each dialect this render covered, keyed by [[kyo.db.Idiom.Id]]
      * @param params
      *   the bind values in placeholder order, each carrying the [[SqlSchema.Column]] that encodes it
      */
    final case class Rendered(perDialect: Map[Idiom.Id, Rendered.Dialected], params: Chunk[BoundValue[?]]) derives CanEqual:

        /** The SQL rendered for `id`, or [[Maybe.Absent]] when this render covered no such dialect. */
        def sqlFor(id: Idiom.Id): Maybe[String] =
            Maybe.fromOption(perDialect.get(id)).map(_.sql)

        /** The SQL of the one dialect this render covered, or [[Maybe.Absent]] when it covered any other number of them.
          *
          * The reading for a render whose dialect the caller chose and therefore need not name again.
          */
        def onlySql: Maybe[String] =
            if perDialect.size == 1 then Maybe.fromOption(perDialect.values.headOption).map(_.sql)
            else Maybe.Absent

        /** [[sqlFor]], failing typed when this render covers no such dialect.
          *
          * The reading the `.run` family's static path emits at its call site, where the dialect comes from the client at run time and the
          * rendered set was fixed when the call site compiled. A client the set does not cover means the application deployed against a
          * backend it was not compiled with, so it fails with [[kyo.SqlStaticRenderMissingDialectException]] naming both sides rather than
          * falling back to another flavor's SQL.
          */
        private[kyo] def sqlForOrFail(id: Idiom.Id)(using Frame): String < Abort[SqlException] =
            sqlFor(id) match
                case Present(sql) => sql
                case Absent       => Abort.fail(SqlStaticRenderMissingDialectException(id, Chunk.from(perDialect.keys)))

    end Rendered

    object Rendered:

        /** One SQL flavor's rendering of a statement: the text, and the server version it was rendered against.
          *
          * The version is what the render resolved, never what the caller passed: a render that named no version carries the dialect's
          * [[kyo.db.Idiom.capabilityFloor]] here. It matters because two renders of the same AST in the same flavor can differ, a
          * statically-rendered statement targets the floor while [[SqlClient.render]] targets the server the client handshook with, and
          * the text alone does not say which.
          */
        final case class Dialected(sql: String, version: Idiom.ServerVersion) derives CanEqual

    end Rendered

    /** A value bound into a statement, paired with the [[SqlSchema.Column]] that encodes it.
      *
      * The canonical spelling in both the render path ([[Sql.Rendered.params]]) and the execution path: the renderer produces one per
      * placeholder it emits, in placeholder order, and the backend consumes them by asking each one's column codec to write its value.
      *
      * The construction site is type-checked: `Sql.BoundValue[A](v: A, s: SqlSchema.Column[A])` enforces that the value and the codec refer to
      * the same type `A`. At storage positions the type parameter is hidden as `Sql.BoundValue[?]`, because one statement's binds are
      * heterogeneous; the backend recovers each encoder from the bind's own column codec.
      *
      * @tparam A
      *   the type of the bound value; must have a [[SqlSchema.Column]] instance
      */
    final case class BoundValue[A](value: A, schema: SqlSchema.Column[A], typeName: String)

    // --- Executable, marker for AST nodes that can be run via SqlClient ---

    /** Marker trait for AST nodes that can be executed via [[kyo.SqlClient]]: [[Query]] (DQL), [[Action]] (DML), and [[Fragment]] (raw
      * SQL).
      */
    sealed trait Executable[A] extends Sql[A]

    // --- Term, the universal expression interface ---

    /** Universal SQL expression interface. Every DSL value, column references, literals, function applications, and boolean combinators,
      * is a `Term[A]`.
      *
      * Symbolic operators (`==`, `!=`, `<`, `<=`, `>`, `>=`, `+`, `-`, `*`, `/`, `%`, `&&`, `||`, `unary_!`, `++`) are defined on `Term`
      * rather than as named methods. CONTRIBUTING.md §195 confines symbolic operators to `kyo-data`, `kyo-prelude`, and `kyo-core`, and a
      * SQL expression DSL is a categorically different context: the operators mirror the SQL they compile to (`col >= 18 &&
      * col.name != ""`), and every operator maps 1-to-1 to a SQL construct with no natural named-method equivalent.
      */
    sealed trait Term[A] extends Sql[A] with SetValue[A]:

        // -- Comparison ------------------------------------------------------

        // Each comparison takes a right side whose type need only agree with the left one up to nullability, which is what
        // [[Sql.SqlComparable]] states. SQL compares two columns the same way whether or not either permits NULL, so requiring the two Scala
        // types to be equal would reject the ordinary join between a nullable foreign key and the non-null primary key it references, and
        // there is no lift from `Term[A]` to `Term[Maybe[A]]` that does not change the SQL.
        //
        // Each operator has three forms: against another term, against a raw value of the column's own type, and against a raw value
        // whose type differs from the column's by nullability. The third is what lets a column permitting NULL be compared to a plain
        // literal, `country == "Germany"` rather than `country == Present("Germany")`. The second stays because it is the one that
        // accepts a `Present(...)` written out: `Present[A]` is its own type rather than an `A`, so it reaches the column's type through
        // the parameter's expected type and not through inference.
        //
        // The third form carries [[Sql.SqlComparableLiteral]] and not the evidence the first one does, because a raw value may cross
        // nullability in one direction only: a value that may be absent, compared against a column that cannot be NULL, is a comparison
        // no row satisfies. See that trait for why it is rejected rather than rendered.

        final inline def ==[B](inline other: Term[B])(using SqlComparable[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Eq, other)

        /** Compares against a value lifted by [[Sql.literal]], which follows the raw-value rule rather than the column one.
          * More specific than the `Term[B]` form above, so a lifted value picks this and a column picks that.
          */
        @targetName("eqLifted")
        final inline def ==[B](inline other: Literal[B])(using SqlComparableLifted[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Eq, other)

        @targetName("eqRaw")
        final inline def ==(inline other: A)(using c: SqlSchema.Column[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Eq, lit(other, c))

        @targetName("eqRawOptional")
        final inline def ==[B](inline other: B)(using ev: SqlComparableLiteral[this.type, A, B], c: SqlSchema.Column[B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Eq, lit(other, c))

        final inline def !=[B](inline other: Term[B])(using SqlComparable[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.NotEq, other)

        /** Compares against a value lifted by [[Sql.literal]], which follows the raw-value rule rather than the column one.
          * More specific than the `Term[B]` form above, so a lifted value picks this and a column picks that.
          */
        @targetName("neLifted")
        final inline def !=[B](inline other: Literal[B])(using SqlComparableLifted[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.NotEq, other)

        @targetName("neqRaw")
        final inline def !=(inline other: A)(using c: SqlSchema.Column[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.NotEq, lit(other, c))

        @targetName("neqRawOptional")
        final inline def !=[B](inline other: B)(using ev: SqlComparableLiteral[this.type, A, B], c: SqlSchema.Column[B]): Term[Boolean] =
            Comparison(this, Comparison.Op.NotEq, lit(other, c))

        final inline def <[B](inline other: Term[B])(using SqlComparable[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lt, other)

        /** Compares against a value lifted by [[Sql.literal]], which follows the raw-value rule rather than the column one.
          * More specific than the `Term[B]` form above, so a lifted value picks this and a column picks that.
          */
        @targetName("ltLifted")
        final inline def <[B](inline other: Literal[B])(using SqlComparableLifted[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lt, other)

        @targetName("ltRaw")
        final inline def <(inline other: A)(using c: SqlSchema.Column[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lt, lit(other, c))

        @targetName("ltRawOptional")
        final inline def <[B](inline other: B)(using ev: SqlComparableLiteral[this.type, A, B], c: SqlSchema.Column[B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lt, lit(other, c))

        final inline def <=[B](inline other: Term[B])(using SqlComparable[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lte, other)

        /** Compares against a value lifted by [[Sql.literal]], which follows the raw-value rule rather than the column one.
          * More specific than the `Term[B]` form above, so a lifted value picks this and a column picks that.
          */
        @targetName("lteLifted")
        final inline def <=[B](inline other: Literal[B])(using SqlComparableLifted[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lte, other)

        @targetName("lteRaw")
        final inline def <=(inline other: A)(using c: SqlSchema.Column[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lte, lit(other, c))

        @targetName("lteRawOptional")
        final inline def <=[B](inline other: B)(using ev: SqlComparableLiteral[this.type, A, B], c: SqlSchema.Column[B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Lte, lit(other, c))

        final inline def >[B](inline other: Term[B])(using SqlComparable[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gt, other)

        /** Compares against a value lifted by [[Sql.literal]], which follows the raw-value rule rather than the column one.
          * More specific than the `Term[B]` form above, so a lifted value picks this and a column picks that.
          */
        @targetName("gtLifted")
        final inline def >[B](inline other: Literal[B])(using SqlComparableLifted[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gt, other)

        @targetName("gtRaw")
        final inline def >(inline other: A)(using c: SqlSchema.Column[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gt, lit(other, c))

        @targetName("gtRawOptional")
        final inline def >[B](inline other: B)(using ev: SqlComparableLiteral[this.type, A, B], c: SqlSchema.Column[B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gt, lit(other, c))

        final inline def >=[B](inline other: Term[B])(using SqlComparable[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gte, other)

        /** Compares against a value lifted by [[Sql.literal]], which follows the raw-value rule rather than the column one.
          * More specific than the `Term[B]` form above, so a lifted value picks this and a column picks that.
          */
        @targetName("gteLifted")
        final inline def >=[B](inline other: Literal[B])(using SqlComparableLifted[this.type, A, B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gte, other)

        @targetName("gteRaw")
        final inline def >=(inline other: A)(using c: SqlSchema.Column[A]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gte, lit(other, c))

        @targetName("gteRawOptional")
        final inline def >=[B](inline other: B)(using ev: SqlComparableLiteral[this.type, A, B], c: SqlSchema.Column[B]): Term[Boolean] =
            Comparison(this, Comparison.Op.Gte, lit(other, c))

        // -- Absence ---------------------------------------------------------
        //
        // Absence is a `Maybe`, so it is asked for with `Absent` and never with a SQL keyword. These two exist rather than letting the raw
        // comparisons carry an `Absent` operand because the answer must not depend on a runtime value: the static path lifts a bind as the
        // call site's own expression, so a renderer that decided between `= ?` and `IS NULL` by inspecting the value would decide wrongly
        // whenever the value is not there to inspect. A dedicated node is the same question asked at the type level.
        //
        // The evidence is what confines them to optional columns: `Absent` conforms to a `Maybe[String]` and not to a `String`, so
        // `age == Absent` on a total column has no applicable overload and the raw comparison rejects it for the ordinary reason, that
        // `Absent` is not an `Int`.

        @targetName("eqAbsent")
        final inline def ==(inline other: Absent)(using Absent <:< A): Term[Boolean] = IsAbsent(this)

        @targetName("neqAbsent")
        final inline def !=(inline other: Absent)(using Absent <:< A): Term[Boolean] = IsPresent(this)

        // -- Ordering specs --------------------------------------------------
        final inline def asc: OrderSpec             = OrderSpec(this, OrderSpec.Direction.Asc, OrderSpec.AbsentPlacement.Default)
        final inline def ascAbsentFirst: OrderSpec  = OrderSpec(this, OrderSpec.Direction.Asc, OrderSpec.AbsentPlacement.First)
        final inline def ascAbsentLast: OrderSpec   = OrderSpec(this, OrderSpec.Direction.Asc, OrderSpec.AbsentPlacement.Last)
        final inline def desc: OrderSpec            = OrderSpec(this, OrderSpec.Direction.Desc, OrderSpec.AbsentPlacement.Default)
        final inline def descAbsentFirst: OrderSpec = OrderSpec(this, OrderSpec.Direction.Desc, OrderSpec.AbsentPlacement.First)
        final inline def descAbsentLast: OrderSpec  = OrderSpec(this, OrderSpec.Direction.Desc, OrderSpec.AbsentPlacement.Last)

        // -- Membership ------------------------------------------------------
        final inline def in(inline values: Term[A]*): Term[Boolean] = InValues(this, Chunk.from(values))

        @targetName("inRaw")
        final inline def in(inline values: A*)(using c: SqlSchema.Column[A]): Term[Boolean] =
            InValues(this, Chunk.from(values).map(v => lit(v, c)))

        /** Membership over raw values that differ from the column by nullability, on the rule `==` follows.
          *
          * The lifted values are cast to the column's parameter because the node holds `Term[A]` and the evidence has already
          * decided the two denote the same SQL type with one of them optional. The cast is erasure-only: a `Term` is a pure
          * description that nothing reads the parameter of at run time, and what is rendered is one bind either way.
          *
          * A nullable column against plain values is the ordinary case: `status.in("open", "held")` on a column that
          * permits NULL. The reverse is refused for the reason the equality form gives, since `IN` renders a chain of
          * equalities and one against NULL is UNKNOWN in both polarities.
          */
        @targetName("inRawOptional")
        final inline def in[B](inline values: B*)(using ev: SqlComparableLiteral[this.type, A, B], c: SqlSchema.Column[B]): Term[Boolean] =
            InValues(this, Chunk.from(values).map(v => lit(v, c).asInstanceOf[Term[A]]))

        final inline def in(inline query: Query[A]): Term[Boolean] = InSubquery(this, query)

        final inline def notIn(inline values: Term[A]*): Term[Boolean] = NotInValues(this, Chunk.from(values))

        @targetName("notInRaw")
        final inline def notIn(inline values: A*)(using c: SqlSchema.Column[A]): Term[Boolean] =
            NotInValues(this, Chunk.from(values).map(v => lit(v, c)))

        /** Non-membership over raw values that differ from the column by nullability. See [[in]]. */
        @targetName("notInRawOptional")
        final inline def notIn[B](inline values: B*)(using
            ev: SqlComparableLiteral[this.type, A, B],
            c: SqlSchema.Column[B]
        ): Term[Boolean] =
            NotInValues(this, Chunk.from(values).map(v => lit(v, c).asInstanceOf[Term[A]]))

        final inline def notIn(inline query: Query[A]): Term[Boolean] = NotInSubquery(this, query)

        // -- Range -----------------------------------------------------------

        final inline def between(inline low: Term[A], inline high: Term[A]): Term[Boolean] = Between(this, low, high)

        @targetName("betweenRaw")
        final inline def between(inline low: A, inline high: A)(using c: SqlSchema.Column[A]): Term[Boolean] =
            Between(this, lit(low, c), lit(high, c))

        /** Range over raw bounds that differ from the column by nullability, on the rule `==` follows. */
        @targetName("betweenRawOptional")
        final inline def between[B](inline low: B, inline high: B)(using
            ev: SqlComparableLiteral[this.type, A, B],
            c: SqlSchema.Column[B]
        ): Term[Boolean] =
            Between(this, lit(low, c).asInstanceOf[Term[A]], lit(high, c).asInstanceOf[Term[A]])

        // -- Coalesce / absentIf ----------------------------------------------
        final def coalesce(others: Term[A]*): Term[A] = Coalesce(this +: Chunk.from(others))

        @targetName("coalesceRaw")
        final def coalesce(others: A*)(using c: SqlSchema.Column[A]): Term[A] =
            Coalesce(this +: Chunk.from(others).map(v => lit(v, c)))

        /** Absent when this expression equals `other`, and this expression's own value otherwise. */
        final def absentIf(other: Term[A]): Term[Maybe[A]] = AbsentIf(this, other)

        @targetName("absentIfRaw")
        final def absentIf(other: A)(using c: SqlSchema.Column[A]): Term[Maybe[A]] =
            AbsentIf(this, lit(other, c))

        // -- Cast ------------------------------------------------------------

        /** `CAST(this AS <B's SQL type>)`, converting this expression to `B` in the database.
          *
          * `B` must have a [[SqlType]], the evidence of a portable SQL column type; a target that has none (for example
          * [[java.time.Duration]], or a multi-column case class) is a compile error here rather than a render-time failure. The type name is
          * chosen when the statement is rendered, not here, because flavors spell the same type differently: a `LocalDateTime` cast is
          * `TIMESTAMP` on Postgres and `DATETIME` on MySQL. A target whose type the flavor cannot cast to still fails the render with
          * [[kyo.SqlUnsupportedDialectFeatureException]] naming it.
          */
        final inline def cast[B](using t: SqlType[B]): Term[B] = Cast(this, t.columnType)

        // -- Labelled term ---------------------------------------------------
        final def as[N <: String & Singleton](label: N): LabelledTerm[N, A] = LabelledTerm(label, this)

        // -- Boolean-only ops (gated by A =:= Boolean) ----------------------
        final inline def &&(inline other: Term[Boolean])(using ev: A =:= Boolean): Term[Boolean] =
            Logical(ev.substituteCo[[T] =>> Term[T]](this), Logical.Op.And, other)

        final inline def ||(inline other: Term[Boolean])(using ev: A =:= Boolean): Term[Boolean] =
            Logical(ev.substituteCo[[T] =>> Term[T]](this), Logical.Op.Or, other)

        final inline def unary_!(using ev: A =:= Boolean): Term[Boolean] = Not(ev.substituteCo[[T] =>> Term[T]](this))

        final def isTrue(using ev: A =:= Boolean): Term[Boolean] =
            BoolTest(ev.substituteCo[[T] =>> Term[T]](this), BoolTest.Predicate.IsTrue)

        final def isNotTrue(using ev: A =:= Boolean): Term[Boolean] =
            BoolTest(ev.substituteCo[[T] =>> Term[T]](this), BoolTest.Predicate.IsNotTrue)

        final def isFalse(using ev: A =:= Boolean): Term[Boolean] =
            BoolTest(ev.substituteCo[[T] =>> Term[T]](this), BoolTest.Predicate.IsFalse)

        final def isNotFalse(using ev: A =:= Boolean): Term[Boolean] =
            BoolTest(ev.substituteCo[[T] =>> Term[T]](this), BoolTest.Predicate.IsNotFalse)

        // -- Maybe[Boolean]-only ops ----------------------------------------
        final def isUnknown(using ev: A =:= Maybe[Boolean]): Term[Boolean]    = IsUnknown(ev.substituteCo[[T] =>> Term[T]](this))
        final def isNotUnknown(using ev: A =:= Maybe[Boolean]): Term[Boolean] = IsNotUnknown(ev.substituteCo[[T] =>> Term[T]](this))

        // -- Numeric-only ops (gated by SqlNumeric[A]) ----------------------
        final inline def +(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic[A](this, Arithmetic.Op.Add, other)

        @targetName("plusRaw")
        final inline def +(inline other: A)(using n: SqlNumeric[A], c: SqlSchema.Column[A]): Term[A] =
            Arithmetic[A](this, Arithmetic.Op.Add, lit(other, c))

        final inline def -(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic[A](this, Arithmetic.Op.Sub, other)

        @targetName("minusRaw")
        final inline def -(inline other: A)(using n: SqlNumeric[A], c: SqlSchema.Column[A]): Term[A] =
            Arithmetic[A](this, Arithmetic.Op.Sub, lit(other, c))

        final inline def *(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic[A](this, Arithmetic.Op.Mul, other)

        @targetName("mulRaw")
        final inline def *(inline other: A)(using n: SqlNumeric[A], c: SqlSchema.Column[A]): Term[A] =
            Arithmetic[A](this, Arithmetic.Op.Mul, lit(other, c))

        /** `this / other`, the fractional quotient.
          *
          * Typed as [[SqlNumeric.Div]] rather than as the operand type, because two integers do not divide into an integer: MySQL answers
          * `SELECT 7/2` with `3.5000`, and PostgreSQL answers the same expression with `3`. Typing the result `BigDecimal` for integral
          * operands and having the PostgreSQL dialect cast so it stops truncating is what makes one source line mean one thing on both
          * flavors. [[divideTruncating]] is the spelling for when truncation is what you want.
          *
          * The two flavors still choose their own SCALE for an exact division that does not terminate: MySQL adds `div_precision_increment`
          * digits (4 by default), PostgreSQL computes at least 16 significant ones. Both are the correct quotient at their own precision;
          * round explicitly if a fixed scale matters.
          */
        final inline def /(inline other: Term[A])(using n: SqlNumeric[A]): Term[n.Div] =
            summonFrom {
                case _: SqlIntegral[A] => Arithmetic[n.Div](this, Arithmetic.Op.DivideIntegral, other)
                case _                 => Arithmetic[n.Div](this, Arithmetic.Op.Divide, other)
            }

        @targetName("divRaw")
        final inline def /(inline other: A)(using n: SqlNumeric[A], c: SqlSchema.Column[A]): Term[n.Div] =
            summonFrom {
                case _: SqlIntegral[A] => Arithmetic[n.Div](this, Arithmetic.Op.DivideIntegral, lit(other, c))
                case _                 => Arithmetic[n.Div](this, Arithmetic.Op.Divide, lit(other, c))
            }

        /** `this / other` truncated toward zero, keeping the integral type.
          *
          * The operators differ: PostgreSQL's integer `/` truncates already, MySQL's does not and spells this `DIV`. Gated on
          * [[SqlIntegral]] because truncating division of a `DECIMAL` is not a portable operation, MySQL's `DIV` truncates one and
          * PostgreSQL's `/` does not.
          */
        final inline def divideTruncating(inline other: Term[A])(using SqlIntegral[A]): Term[A] =
            Arithmetic[A](this, Arithmetic.Op.DivideTruncating, other)

        @targetName("divideTruncatingRaw")
        final inline def divideTruncating(inline other: A)(using i: SqlIntegral[A], c: SqlSchema.Column[A]): Term[A] =
            Arithmetic[A](this, Arithmetic.Op.DivideTruncating, lit(other, c))

        final inline def %(inline other: Term[A])(using SqlNumeric[A]): Term[A] = Arithmetic[A](this, Arithmetic.Op.Mod, other)

        @targetName("modRaw")
        final inline def %(inline other: A)(using n: SqlNumeric[A], c: SqlSchema.Column[A]): Term[A] =
            Arithmetic[A](this, Arithmetic.Op.Mod, lit(other, c))

        final def abs(using SqlNumeric[A]): Term[A]     = NumericFn(this, NumericFn.Op.Abs)
        final def ceiling(using SqlNumeric[A]): Term[A] = NumericFn(this, NumericFn.Op.Ceiling)
        final def floor(using SqlNumeric[A]): Term[A]   = NumericFn(this, NumericFn.Op.Floor)
        final def round(using SqlNumeric[A]): Term[A]   = NumericFn(this, NumericFn.Op.Round)

        // -- String-only ops (gated by A =:= String) ------------------------
        // asStr must remain inline so that callers used in staticSql expressions are macro-liftable.
        private inline def asStr(using ev: A =:= String): Term[String] = ev.substituteCo[[T] =>> Term[T]](this)

        /** The same term seen as text, for a column whose type is `String` or `Maybe[String]`.
          *
          * The pattern operators answer `Term[Boolean]` whatever the operand's nullability, because a NULL operand makes
          * the predicate UNKNOWN and a WHERE drops the row, which is what a caller asking `LIKE` on a column that permits
          * NULL means. The node carries `Term[String]` and the cast is erasure-only: `Term` is a pure description and
          * nothing reads the parameter at run time. `asStr` above stays for the operators that RETURN text, where the
          * operand's nullability belongs in the result type and this substitution would lose it.
          */
        private inline def asTextual: Term[String]                                        = this.asInstanceOf[Term[String]]
        final def upper(using A =:= String): Term[String]                                 = StringFn(asStr, StringFn.Op.Upper)
        final def lower(using A =:= String): Term[String]                                 = StringFn(asStr, StringFn.Op.Lower)
        final def length(using A =:= String): Term[Int]                                   = StringLength(asStr)
        final def trim(using A =:= String): Term[String]                                  = StringFn(asStr, StringFn.Op.Trim)
        final inline def ++(inline other: Term[String])(using A =:= String): Term[String] = Concat(Chunk(asStr, other))

        @targetName("concatRaw")
        final inline def ++(inline other: String)(using A =:= String): Term[String] =
            Concat(Chunk(asStr, lit(other, SqlSchema.string)))

        final inline def like(inline pattern: Term[String])(using SqlTextual[this.type, A]): Term[Boolean] =
            StringMatch(asTextual, StringMatch.Op.Like, pattern)

        @targetName("likeRaw")
        final inline def like(inline pattern: String)(using SqlTextual[this.type, A]): Term[Boolean] =
            StringMatch(asTextual, StringMatch.Op.Like, lit(pattern, SqlSchema.string))

        final inline def notLike(inline pattern: Term[String])(using SqlTextual[this.type, A]): Term[Boolean] =
            StringMatch(asTextual, StringMatch.Op.NotLike, pattern)

        @targetName("notLikeRaw")
        final inline def notLike(inline pattern: String)(using SqlTextual[this.type, A]): Term[Boolean] =
            StringMatch(asTextual, StringMatch.Op.NotLike, lit(pattern, SqlSchema.string))

        final inline def ilike(inline pattern: Term[String])(using SqlTextual[this.type, A]): Term[Boolean] =
            StringMatch(asTextual, StringMatch.Op.ILike, pattern)

        @targetName("ilikeRaw")
        final inline def ilike(inline pattern: String)(using SqlTextual[this.type, A]): Term[Boolean] =
            StringMatch(asTextual, StringMatch.Op.ILike, lit(pattern, SqlSchema.string))

        final inline def notIlike(inline pattern: Term[String])(using SqlTextual[this.type, A]): Term[Boolean] =
            StringMatch(asTextual, StringMatch.Op.NotILike, pattern)

        @targetName("notIlikeRaw")
        final inline def notIlike(inline pattern: String)(using SqlTextual[this.type, A]): Term[Boolean] =
            StringMatch(asTextual, StringMatch.Op.NotILike, lit(pattern, SqlSchema.string))

        final inline def substring(inline start: Term[Int])(using A =:= String): Term[String] = Substring(asStr, start, Maybe.empty)

        @targetName("substringRaw1")
        final inline def substring(inline start: Int)(using A =:= String): Term[String] =
            Substring(asStr, lit(start, SqlSchema.int), Maybe.empty)

        final inline def substring(inline start: Term[Int], inline length: Term[Int])(using A =:= String): Term[String] =
            Substring(asStr, start, Maybe(length))

        @targetName("substringRaw2")
        final inline def substring(inline start: Int, inline length: Int)(using A =:= String): Term[String] =
            Substring(asStr, lit(start, SqlSchema.int), Maybe(lit(length, SqlSchema.int)))
    end Term

    // --- Concrete Term subtypes ---

    /** A column reference carrying both the column's name (singleton type) and value type.
      *
      * @param alias
      *   the table alias this column belongs to (empty string for row-level columns).
      * @param name
      *   the Scala field name (singleton type N encodes it statically).
      * @param sqlName
      *   the SQL-side name to emit in rendered SQL (post-rename, post-naming-strategy). Equals `name` when no naming transforms are
      *   applied.
      */
    final case class Column[N <: String & Singleton, V](alias: String, name: N, sqlName: String) extends Term[V] derives CanEqual:

        /** Assigns `other` to this column, in an `UPDATE ... SET`, an `INSERT ... VALUES` built from named columns, or a conflict-update
          * clause.
          *
          * Takes a [[SetValue]] rather than a [[Term]], which is the same thing plus one alternative: `Sql.default`, the column's own
          * declared default. That alternative exists only here, because `DEFAULT` is not an expression and no server accepts it anywhere
          * else a term may appear.
          */
        inline def :=(inline other: SetValue[V]): SetSpec[N, V] = SetSpec(this, other)

        @targetName("assignRaw")
        inline def :=(inline other: V)(using c: SqlSchema.Column[V]): SetSpec[N, V] = SetSpec(this, lit(other, c))

        // Window-only aggregates (return WindowAggregate, NOT a Term).
        //
        // `sum` and `avg` are typed from SqlNumeric, because neither server returns the operand's own type: see SqlNumeric's
        // scaladoc for the widths. `min` and `max` keep `V`, which is the one width both flavors do return unchanged, and they
        // take no SqlNumeric because MIN and MAX are defined over any ordered type. An optional operand carries its own `Maybe`
        // through all four, since SqlNumeric maps `Maybe[A]` to a `Maybe` result and V is already the `Maybe` for min and max.
        //
        // There is deliberately no DISTINCT variant here. PostgreSQL rejects `SUM(DISTINCT x) OVER (...)` with "DISTINCT is not
        // implemented for window functions", and MySQL 8 lists DISTINCT for aggregate window functions among the features it
        // does not support, so no server accepts the statement such a method would build. DISTINCT aggregates are offered only
        // where they are valid, without OVER: `From.sumDistinct`, `Where.sumDistinct`, `GroupTerm.sumDistinct` and siblings.
        inline def sum(using n: SqlNumeric[V]): WindowAggregate[n.Sum] = WindowAggregate(Aggregate.Sum[n.Sum](this, distinct = false))
        inline def avg(using n: SqlNumeric[V]): WindowAggregate[n.Avg] = WindowAggregate(Aggregate.Avg[n.Avg](this, distinct = false))
        inline def min: WindowAggregate[V]                             = WindowAggregate(Aggregate.Min[V](this))
        inline def max: WindowAggregate[V]                             = WindowAggregate(Aggregate.Max[V](this))
        inline def count: WindowAggregate[Long]                        = WindowAggregate(Aggregate.Count(Maybe(this), distinct = false))

        // Window-function constructors (return WindowFunction; only `.over(spec)` produces a Term)
        inline def lead: WindowFunction[V] = lead(oneInt)

        @targetName("leadInt")
        inline def lead(inline offset: Int): WindowFunction[V] =
            lead(intLit(offset))

        inline def lead(inline offset: Term[Int]): WindowFunction[V] = WindowFunction.Lead(this, offset, Maybe.empty)

        @targetName("leadIntRaw")
        inline def lead(inline offset: Int, inline default: V)(using c: SqlSchema.Column[V]): WindowFunction[V] =
            lead(intLit(offset), lit(default, c))

        inline def lead(inline offset: Term[Int], inline default: Term[V]): WindowFunction[V] =
            WindowFunction.Lead(this, offset, Maybe(default))

        inline def lag: WindowFunction[V] = lag(oneInt)

        @targetName("lagInt")
        inline def lag(inline offset: Int): WindowFunction[V] = lag(intLit(offset))

        inline def lag(inline offset: Term[Int]): WindowFunction[V] = WindowFunction.Lag(this, offset, Maybe.empty)

        @targetName("lagIntRaw")
        inline def lag(inline offset: Int, inline default: V)(using c: SqlSchema.Column[V]): WindowFunction[V] =
            lag(intLit(offset), lit(default, c))

        inline def lag(inline offset: Term[Int], inline default: Term[V]): WindowFunction[V] =
            WindowFunction.Lag(this, offset, Maybe(default))

        inline def firstValue: WindowFunction[V] = WindowFunction.FirstValue(this)

        inline def lastValue: WindowFunction[V] = WindowFunction.LastValue(this)

        @targetName("nthValueInt")
        inline def nthValue(inline n: Int): WindowFunction[V] = WindowFunction.NthValue(this, intLit(n))

        inline def nthValue(inline n: Term[Int]): WindowFunction[V] = WindowFunction.NthValue(this, n)
    end Column

    final case class Literal[A](value: A, schema: SqlSchema.Column[A], typeName: String) extends Term[A]

    /** A term carrying an explicit label. Created via `term.as("name")`, used by `groupBy` / `select` for labelled projections. The label
      * is preserved by the renderer when it generates `expr AS "name"`; in non-projection contexts (WHERE, etc.) the label is irrelevant
      * and the inner term is rendered.
      */
    final case class LabelledTerm[N <: String & Singleton, A](label: N, term: Term[A]) extends Term[A] derives CanEqual

    /** Evidence that a `Term[A]` and a `Term[B]` may be compared: the two denote the same SQL type, with either side optional.
      *
      * Nullability is a property of the Scala type, not of the comparison: SQL compares a column that permits NULL to one that does not
      * exactly as it compares two of the same kind. Requiring both sides to be the same Scala type would reject the ordinary join between
      * a nullable foreign key and the non-null primary key it references, which is the first join written against most schemas, and the
      * DSL has no lift from `Term[A]` to `Term[Maybe[A]]` that leaves the SQL alone: `coalesce` stays inside `Maybe`, and `cast` emits a
      * SQL CAST, which is a different query and can cost an index.
      *
      * The three instances are the three shapes: both total, the left optional, the right optional. Two optional sides are the first
      * instance, since the types are then equal.
      */
    @implicitNotFound(
        "Cannot compare a SQL expression of type ${A} with one of type ${B}.\n" +
            "The two sides of a comparison must denote the same SQL type; one of them may be optional (kyo.Maybe), which is what a\n" +
            "column permitting NULL is, so a nullable foreign key compares against the non-null key it references. Two different SQL\n" +
            "types do not compare: spell the conversion with `.cast`."
    )
    sealed trait SqlComparable[S, A, B]

    object SqlComparable:
        private val evidence: SqlComparable[Any, Any, Any] = new SqlComparable[Any, Any, Any] {}

        /** Both sides are the same Scala type, optional or not. */
        given same[S, A](using NotGiven[S <:< Literal[?]]): SqlComparable[S, A, A] =
            evidence.asInstanceOf[SqlComparable[S, A, A]]

        /** The left side permits NULL and the right does not. Guarded off a lifted receiver: that side is then a value rather than a
          * column, and the argument keeping this instance does not reach it.
          */
        given leftOptional[S, A](using NotGiven[S <:< Literal[?]]): SqlComparable[S, Maybe[A], A] =
            evidence.asInstanceOf[SqlComparable[S, Maybe[A], A]]

        /** The right side permits NULL and the left does not. */
        given rightOptional[S, A](using NotGiven[S <:< Literal[?]]): SqlComparable[S, A, Maybe[A]] =
            evidence.asInstanceOf[SqlComparable[S, A, Maybe[A]]]

        /** A lifted receiver against a term of its own type. */
        given liftedSame[S <: Literal[?], A]: SqlComparable[S, A, A] =
            evidence.asInstanceOf[SqlComparable[S, A, A]]

        /** A lifted receiver against a term that permits NULL where the lifted value does not, which is the one direction a value may
          * cross. The reverse has no instance, which is what refuses `Sql.literal(absent) == notNullColumn`.
          */
        given liftedArgOptional[S <: Literal[?], A]: SqlComparable[S, A, Maybe[A]] =
            evidence.asInstanceOf[SqlComparable[S, A, Maybe[A]]]
    end SqlComparable

    /** Evidence that a column of type `A` may be compared against a RAW value of type `B` that differs from it by nullability.
      *
      * One instance, in one direction: the column permits NULL and the value does not. That is the case the raw form exists for,
      * `country == "Germany"` on a nullable column rather than `country == Present("Germany")`.
      *
      * The other direction is deliberately absent, and this is why [[SqlComparable]] cannot serve here. A column that cannot be NULL
      * compared against a value that may be is a question no row can answer: `= NULL` is unknown for every row, so the comparison matches
      * nothing, and so does its negation: `x = NULL` is UNKNOWN for every row, `NOT UNKNOWN` is UNKNOWN too, and a WHERE keeps neither.
      * A predicate no row can satisfy in either polarity is one the author cannot have meant, and it fails silently and at runtime.
      * Deciding between `= ?` and `IS NULL` by looking at the value
      * is not available either, for the reason the absence operators below give: the static path lifts the bind as the call site's own
      * expression, so there is no value to look at. Rejecting it is the only answer that reports anything.
      *
      * A `Term[A]` against a `Term[Maybe[A]]` stays allowed, on [[SqlComparable]]: comparing two columns says nothing about whether
      * either is NULL, and the join between a non-null key and the nullable foreign key referencing it is written that way round as often
      * as the other.
      */
    @implicitNotFound(
        "Cannot compare a SQL column of type ${A} with a raw value of type ${B}.\n" +
            "Either the two are different SQL types, in which case the value's type is the thing to fix; or they differ only by\n" +
            "nullability, which a raw value may do in one direction only: the column permits NULL and the value does not, as in\n" +
            "`country == \"Germany\"` on a nullable column. The other way round matches no row, and neither does its negation, because\n" +
            "nothing compares equal to NULL. To ask whether the column is absent, compare against `Absent`."
    )
    sealed trait SqlComparableLiteral[S, A, B]

    object SqlComparableLiteral:
        private val evidence: SqlComparableLiteral[Any, Any, Any] = new SqlComparableLiteral[Any, Any, Any] {}

        /** The column permits NULL and the raw value does not.
          *
          * No instance exists for a lifted receiver: a lifted value against a raw value is two values, and the one that may be absent
          * makes the comparison the `= NULL` no row satisfies, exactly as it does against a column.
          */
        given optionalColumn[S, A](using NotGiven[S <:< Literal[?]]): SqlComparableLiteral[S, Maybe[A], A] =
            evidence.asInstanceOf[SqlComparableLiteral[S, Maybe[A], A]]
    end SqlComparableLiteral

    /** Evidence that a column of type `A` may be compared against a value LIFTED into a term by [[Sql.literal]].
      *
      * The same rule [[SqlComparableLiteral]] states, and for the same reason: a lifted value is a value, not a column, so the argument
      * that keeps [[SqlComparable.rightOptional]] does not reach it. Comparing two columns says nothing about whether either is NULL,
      * which is why the join between a non-null key and the nullable foreign key referencing it is allowed; a lifted `Maybe` against a
      * NOT NULL column is the `= NULL` no row satisfies in either polarity, spelled through a different door.
      *
      * Carries the same-type case as well, which [[SqlComparableLiteral]] does not need: a raw value of the column's own type is served by
      * the overload taking `A` exactly, and a lifted one arrives as a `Literal[A]` that overload cannot match.
      */
    @implicitNotFound(
        "Cannot compare a SQL column of type ${A} with a lifted value of type ${B}.\n" +
            "A lifted value follows the rule a raw one does: it may differ from the column by nullability only in the direction\n" +
            "where the column permits NULL and the value does not. The other way round matches no row, and neither does its\n" +
            "negation, because nothing compares equal to NULL. To ask whether the column is absent, compare against `Absent`."
    )
    sealed trait SqlComparableLifted[S, A, B]

    /** Evidence that a column of type `A` holds text, whether or not it permits NULL.
      *
      * The pattern operators (`like`, `ilike` and their negations) need this rather than `A =:= String`, which admits only a column that
      * cannot be NULL. `name LIKE 'a%'` on a nullable column is ordinary SQL, and it answers UNKNOWN for the rows where the column is
      * absent, which a WHERE drops. Before this the operators simply did not exist on a `Maybe[String]` column, in either form.
      *
      * Only the operators answering `Term[Boolean]` take it. The ones that RETURN text keep `A =:= String`, because there the operand's
      * nullability belongs in the result type: `upper` on a column that may be absent may itself be absent.
      */
    sealed trait SqlTextual[S, A]

    object SqlTextual:
        private val evidence: SqlTextual[Any, Any] = new SqlTextual[Any, Any] {}

        /** A text column that cannot be NULL. */
        given text[S](using NotGiven[S <:< Literal[?]]): SqlTextual[S, String] =
            evidence.asInstanceOf[SqlTextual[S, String]]

        /** A text column that permits NULL. */
        given optionalText[S](using NotGiven[S <:< Literal[?]]): SqlTextual[S, Maybe[String]] =
            evidence.asInstanceOf[SqlTextual[S, Maybe[String]]]

        /** A lifted text value, which is present by construction. A lifted value that may be absent has no instance: `? LIKE '%'` with a
          * NULL bind is UNKNOWN for every row, the same never-matching predicate the comparisons refuse.
          */
        given liftedText[S <: Literal[?]]: SqlTextual[S, String] =
            evidence.asInstanceOf[SqlTextual[S, String]]
    end SqlTextual

    object SqlComparableLifted:
        private val evidence: SqlComparableLifted[Any, Any, Any] = new SqlComparableLifted[Any, Any, Any] {}

        /** The lifted value has the column's own type. */
        given same[S, A](using NotGiven[S <:< Literal[?]]): SqlComparableLifted[S, A, A] =
            evidence.asInstanceOf[SqlComparableLifted[S, A, A]]

        /** The column permits NULL and the lifted value does not. */
        given optionalColumn[S, A](using NotGiven[S <:< Literal[?]]): SqlComparableLifted[S, Maybe[A], A] =
            evidence.asInstanceOf[SqlComparableLifted[S, Maybe[A], A]]

        /** Both sides lifted, which is two values rather than a value and a column: equal types only, so a lifted absent value is
          * refused against a lifted present one in either order.
          */
        given liftedSame[S <: Literal[?], A]: SqlComparableLifted[S, A, A] =
            evidence.asInstanceOf[SqlComparableLifted[S, A, A]]
    end SqlComparableLifted

    /** A binary comparison. The operands carry no shared type parameter: [[Sql.SqlComparable]] has already decided at the call site that
      * the two sides denote the same SQL type, and what is rendered is the same either way.
      */
    final case class Comparison(left: Term[?], op: Comparison.Op, right: Term[?]) extends Term[Boolean] derives CanEqual
    object Comparison:
        enum Op derives CanEqual:
            case Eq, NotEq, Lt, Lte, Gt, Gte

    final case class Logical(left: Term[Boolean], op: Logical.Op, right: Term[Boolean]) extends Term[Boolean] derives CanEqual
    object Logical:
        enum Op derives CanEqual:
            case And, Or

    final case class Not(expr: Term[Boolean]) extends Term[Boolean] derives CanEqual

    final case class BoolTest(expr: Term[Boolean], pred: BoolTest.Predicate) extends Term[Boolean] derives CanEqual
    object BoolTest:
        enum Predicate derives CanEqual:
            case IsTrue, IsNotTrue, IsFalse, IsNotFalse

    /** `expr` holds no value, the node `col == Absent` builds. Carries no bind, so it renders the same whatever the row contains. */
    final case class IsAbsent[A](expr: Term[A]) extends Term[Boolean] derives CanEqual

    /** `expr` holds a value, the node `col != Absent` builds, true for exactly the rows [[IsAbsent]] is false for. */
    final case class IsPresent[A](expr: Term[A]) extends Term[Boolean] derives CanEqual

    final case class IsUnknown(expr: Term[Maybe[Boolean]])    extends Term[Boolean] derives CanEqual
    final case class IsNotUnknown(expr: Term[Maybe[Boolean]]) extends Term[Boolean] derives CanEqual

    final case class Between[A](expr: Term[A], low: Term[A], high: Term[A]) extends Term[Boolean] derives CanEqual

    final case class InValues[A](expr: Term[A], values: Chunk[Term[A]])    extends Term[Boolean] derives CanEqual
    final case class NotInValues[A](expr: Term[A], values: Chunk[Term[A]]) extends Term[Boolean] derives CanEqual

    final case class InSubquery[A](expr: Term[A], subquery: Query[A])    extends Term[Boolean] derives CanEqual
    final case class NotInSubquery[A](expr: Term[A], subquery: Query[A]) extends Term[Boolean] derives CanEqual

    final case class Exists(subquery: Query[?])    extends Term[Boolean] derives CanEqual
    final case class NotExists(subquery: Query[?]) extends Term[Boolean] derives CanEqual

    final case class ScalarSub[A](subquery: Query[A]) extends Term[A] derives CanEqual

    final case class Coalesce[A](exprs: Chunk[Term[A]]) extends Term[A] derives CanEqual

    final case class AbsentIf[A](left: Term[A], right: Term[A]) extends Term[Maybe[A]] derives CanEqual

    /** `CAST(expr AS …)`, where the target's portable [[SqlType.Type]] is carried directly and spelled by the dialect at render time. The
      * static-SQL lift reconstructs it through a custom `FromExpr` ([[kyo.internal.macros.ColumnFromExpr]]) that resolves the summoned
      * `SqlType` given by reference, since a summoned given arrives as a reference rather than a literal enum the generic walker could read.
      */
    final case class Cast[A, B](expr: Term[A], target: SqlType.Type) extends Term[B] derives CanEqual

    final case class Concat(parts: Chunk[Term[String]]) extends Term[String] derives CanEqual

    /** `left op right`. `A` is the type of the value the server returns, which for [[Arithmetic.Op.DivideIntegral]] is not the operands'. */
    final case class Arithmetic[A](left: Term[?], op: Arithmetic.Op, right: Term[?]) extends Term[A] derives CanEqual
    object Arithmetic:

        /** The arithmetic operators, with division split three ways because the quotient is the one arithmetic result the two flavors do not
          * agree on, and they disagree in two independent places.
          *
          * PostgreSQL's `int4 / int4` truncates and returns an `int4`, while MySQL's `/` is fractional for every operand type and reserves a
          * separate `DIV` operator for the truncated quotient. A single division op would therefore either truncate on one flavor and not the
          * other, or oblige each dialect to know the operands' Scala types, which are erased by render time. Naming the three cases where the
          * types are still in hand is what lets each dialect render its own answer.
          */
        enum Op derives CanEqual:
            case Add, Sub, Mul, Mod

            /** `left / right` on non-integral operands, where both flavors' `/` already yields the fractional quotient. */
            case Divide

            /** `left / right` on integral operands, yielding the fractional quotient. PostgreSQL's integer division truncates, so its dialect
              * casts to reach the quotient this asks for; MySQL needs no cast.
              */
            case DivideIntegral

            /** `left / right` on integral operands, truncated toward zero. PostgreSQL's integer `/` is already this; MySQL spells it `DIV`. */
            case DivideTruncating
        end Op
    end Arithmetic

    final case class NumericFn[A](expr: Term[A], op: NumericFn.Op) extends Term[A] derives CanEqual
    object NumericFn:
        enum Op derives CanEqual:
            case Abs, Ceiling, Floor, Round

    final case class StringFn(expr: Term[String], op: StringFn.Op) extends Term[String] derives CanEqual
    object StringFn:
        enum Op derives CanEqual:
            case Upper, Lower, Trim

    final case class StringLength(expr: Term[String]) extends Term[Int] derives CanEqual

    final case class StringMatch(expr: Term[String], op: StringMatch.Op, pattern: Term[String]) extends Term[Boolean] derives CanEqual
    object StringMatch:
        enum Op derives CanEqual:
            case Like, NotLike, ILike, NotILike

    final case class Substring(expr: Term[String], start: Term[Int], length: Maybe[Term[Int]]) extends Term[String] derives CanEqual

    final case class CaseExpr[A](whens: Chunk[(Term[Boolean], Term[A])], otherwise: Term[A]) extends Term[A] derives CanEqual
    final case class CaseExprMaybe[A](whens: Chunk[(Term[Boolean], Term[A])])                extends Term[Maybe[A]] derives CanEqual

    final case class FunctionCall[A](name: String, args: Chunk[Term[?]]) extends Term[A] derives CanEqual

    final case class RawSql[A](sql: String) extends Term[A] derives CanEqual

    /** A composable SQL fragment: a sequence of [[Fragment.Part]]s alternating between literal text, bound values, and embedded [[Term]]s.
      * Built by the `sql"..."` interpolator in `Sql.scala` or by lifting raw values via the companion. Composes via `++`; usable anywhere a
      * `Term[A]` is expected.
      */
    final case class Fragment[A](parts: Chunk[Fragment.Part]) extends Executable[A] with Term[A]:
        /** Concatenate two fragments. The output type widens to the right-hand side (matches Doobie's convention). */
        def ++[B](other: Fragment[B]): Fragment[B] = Fragment[B](parts ++ other.parts)

        /** Re-tag the phantom output type. */
        def as[B]: Fragment[B] = Fragment[B](parts)
    end Fragment

    object Fragment:

        // Extensions live in this companion, like the four executable siblings (Query, Insert, Update, Delete), so they
        // are found by implicit search on any Fragment[A] receiver without polluting the top-level namespace.
        extension [A](self: Fragment[A])

            /** Runs the fragment as a query on the installed client and decodes each row as `A` through the [[SqlSchema]] evidence, the
              * ambient counterpart of the typed DSL's `run`: raw SQL and typed queries share the execution path and the decode. The
              * in-scope [[SqlNaming]] casing threads into the by-name decode exactly as the DSL `run` threads it.
              */
            inline def run(using SqlSchema[A], Frame): Chunk[A] < (Abort[SqlException] & DB) =
                fragmentRun(self, summonInline[SqlSchema[A]], fragmentNaming)

            /** Runs the fragment as a DML statement on the installed client and returns the affected-row count. */
            def execute(using Frame): Long < (Abort[SqlException] & DB) =
                DB.client.map(_.execute(self))

            /** Streams the fragment's rows from the installed client, decoding each as `A`; the portal is closed when the enclosing
              * [[Scope]] ends, which is why streaming alone among the run forms carries `Scope`.
              */
            inline def stream(using SqlSchema[A], Tag[Emit[Chunk[A]]], Frame): Stream[A, Abort[SqlException] & Scope & DB] =
                fragmentStream(self, summonInline[SqlSchema[A]], fragmentNaming)
        end extension

        /** The in-scope [[SqlNaming]] at the call site, `Identity` when none is given. */
        private inline def fragmentNaming: SqlNaming =
            summonFrom {
                case n: SqlNaming => n
                case _            => SqlNaming.Identity
            }

        private def fragmentRun[A](f: Fragment[A], ev: SqlSchema[A], naming: SqlNaming)(using
            Frame
        ): Chunk[A] < (Abort[SqlException] & DB) =
            DB.client.map(_.query(f).map(rows => Kyo.foreach(rows)(row => row.decodeRow(ev, Maybe(naming), SqlRow.FieldMatch.ByName))))

        private def fragmentStream[A](f: Fragment[A], ev: SqlSchema[A], naming: SqlNaming)(using
            Tag[Emit[Chunk[A]]],
            Frame
        ): Stream[A, Async & Abort[SqlException] & Scope & DB] =
            Stream.unwrap(DB.client.map(c => c.streamQuery(f).map(row => row.decodeRow(ev, Maybe(naming), SqlRow.FieldMatch.ByName))))

        sealed trait Part
        final case class Lit(text: String)                                                extends Part derives CanEqual
        final case class Bind[A](value: A, schema: SqlSchema.Column[A], typeName: String) extends Part
        final case class Embed(term: Term[?])                                             extends Part derives CanEqual

        /** An empty fragment, identity for `++`. */
        def empty[A]: Fragment[A] = Fragment[A](Chunk.empty)

        /** A literal-text-only fragment (no binds, no embeds). */
        def lit[A](text: String): Fragment[A] = Fragment[A](Chunk(Lit(text)))
    end Fragment

    final case class Excluded[N <: String & Singleton, V](column: Column[N, V]) extends Term[V] derives CanEqual

    final case class Windowed[A](
        inner: Aggregate.Call[A] | WindowFunction[A],
        spec: WindowSpec
    ) extends Term[A] derives CanEqual

    // --- Aggregates, sealed family of call nodes (Term[A]) ---

    /** The aggregate call nodes.
      *
      * Every node's type parameter is the type of the VALUE THE SERVER RETURNS, not the operand's, and the operand is a `Term[?]`. The two
      * are routinely different: `COUNT` over anything is a `bigint`, `SUM` over an `INTEGER` is wider than an `INTEGER` on both flavors, and
      * an aggregate over an empty input has no value whatever the operand was. Each DSL entry point states the result type explicitly when
      * it builds a node, from [[SqlNumeric]] for the width and [[AsMaybe]] for the absence its own context implies.
      */
    object Aggregate:
        sealed abstract class Call[A]                                   extends Term[A]
        final case class Count(expr: Maybe[Term[?]], distinct: Boolean) extends Call[Long] derives CanEqual
        final case class Sum[A](expr: Term[?], distinct: Boolean)       extends Call[A] derives CanEqual
        final case class Avg[A](expr: Term[?], distinct: Boolean)       extends Call[A] derives CanEqual
        final case class Min[A](expr: Term[?])                          extends Call[A] derives CanEqual
        final case class Max[A](expr: Term[?])                          extends Call[A] derives CanEqual
    end Aggregate

    // --- WindowAggregate, wraps an Aggregate.Call, only usable via .over(spec) ---

    final case class WindowAggregate[A](inner: Aggregate.Call[A]) derives CanEqual:
        inline def over(inline spec: WindowSpec): Term[A]            = Windowed(inner, spec)
        inline def over(inline builder: WindowSpec.Builder): Term[A] = Windowed(inner, builder.build)

    // --- WindowFunction, column-based and standalone window functions ---

    sealed trait WindowFunction[A]:
        inline def over(inline spec: WindowSpec): Term[A]            = Windowed(this, spec)
        inline def over(inline builder: WindowSpec.Builder): Term[A] = Windowed(this, builder.build)

    object WindowFunction:
        given CanEqual[WindowFunction[?], WindowFunction[?]] = CanEqual.derived

        case object RowNumber                                                               extends WindowFunction[Long]
        case object Rank                                                                    extends WindowFunction[Long]
        case object DenseRank                                                               extends WindowFunction[Long]
        case object PercentRank                                                             extends WindowFunction[Double]
        case object CumeDist                                                                extends WindowFunction[Double]
        final case class Ntile(n: Term[Int])                                                extends WindowFunction[Int] derives CanEqual
        final case class Lead[A](expr: Term[A], offset: Term[Int], default: Maybe[Term[A]]) extends WindowFunction[A] derives CanEqual
        final case class Lag[A](expr: Term[A], offset: Term[Int], default: Maybe[Term[A]])  extends WindowFunction[A] derives CanEqual
        final case class FirstValue[A](expr: Term[A])                                       extends WindowFunction[A] derives CanEqual
        final case class LastValue[A](expr: Term[A])                                        extends WindowFunction[A] derives CanEqual
        final case class NthValue[A](expr: Term[A], n: Term[Int])                           extends WindowFunction[A] derives CanEqual
    end WindowFunction

    // --- OrderSpec / WindowSpec / Frame / FrameBound ---

    final case class OrderSpec(expr: Term[?], direction: OrderSpec.Direction, absent: OrderSpec.AbsentPlacement) derives CanEqual
    object OrderSpec:
        enum Direction derives CanEqual:
            case Asc, Desc

        /** Where absent values sort relative to present ones, or [[AbsentPlacement.Default]] to leave it to the flavor. */
        enum AbsentPlacement derives CanEqual:
            case Default, First, Last
    end OrderSpec

    final case class WindowSpec(
        partitionBy: Chunk[Term[?]],
        orderBy: Chunk[OrderSpec],
        frame: Maybe[WindowFrame]
    ) derives CanEqual

    object WindowSpec:

        /** Window-spec builder. Terminator methods (`rowNumber`, `rank`, etc.) finalise to a `Term[A]`.
          *
          * Builder methods use explicit `new Builder(...)` constructors rather than `.copy(...)` with `Chunk.from` / `:+` so that the
          * inlined call-site trees are liftable by `FromExpr.derived` (the static-SQL macro). `Chunk(key)` / `Chunk(keys*)` emit a
          * `Chunk.apply` varargs tree which the `FromExpr[Chunk[A]]` derivation recognises; `.copy(...)` and `Chunk.from(keys)` produce
          * runtime method calls that the macro cannot reduce.
          *
          * ===Static-SQL note===
          *
          * The builder chain `Sql.windowSpec.partitionBy(x).rowNumber` lifts cleanly through the compile-time `.runStatic` path; no
          * explicit `WindowSpec(...)` constructor is required at static call sites.
          *
          * ===Replace semantics===
          *
          * `partitionBy(b)` after `partitionBy(a)` replaces the partition list; it does NOT append. The resulting `Builder.partitions`
          * field is `Chunk(b)`, not `Chunk(a, b)`. Use the vararg overload `partitionBy(a, b)` for multi-key partitions.
          *
          * Similarly, `orderBy(spec2)` after `orderBy(spec1)` replaces the ordering list with `Chunk(spec2)`.
          */
        final case class Builder(
            partitions: Chunk[Term[?]],
            orderings: Chunk[OrderSpec],
            frameOpt: Maybe[WindowFrame]
        ) derives CanEqual:
            // -- build chain (return fresh builder) ------------------------------------

            /** Replace the partition list with `Chunk(key)`.
              *
              * @note
              *   replace semantic, calling `partitionBy(b)` after `partitionBy(a)` yields `partitions = Chunk(b)`, not `Chunk(a, b)`. Use
              *   `partitionBy(a, b)` for multi-key partitions.
              */
            inline def partitionBy[N <: String & Singleton, V](inline key: Column[N, V]): Builder =
                new Builder(Chunk(key), orderings, frameOpt)

            /** Replace the partition list with `Chunk(keys*)`.
              *
              * @note
              *   replace semantic, calling `partitionBy(b)` after `partitionBy(a)` yields `partitions = Chunk(b)`, not `Chunk(a, b)`.
              */
            @targetName("partitionByVarargs")
            inline def partitionBy(inline keys: Term[?]*): Builder =
                new Builder(Chunk(keys*), orderings, frameOpt)

            /** Replace the ordering list with `Chunk(spec)`. */
            inline def orderBy(inline spec: OrderSpec): Builder =
                new Builder(partitions, Chunk(spec), frameOpt)

            /** Replace the ordering list with `Chunk(specs*)`. */
            @targetName("orderByVarargs")
            inline def orderBy(inline specs: OrderSpec*): Builder =
                new Builder(partitions, Chunk(specs*), frameOpt)

            inline def frameRows(inline start: FrameBound, inline end: FrameBound): Builder =
                new Builder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Rows, start, Maybe(end))))
            inline def frameRows(inline start: FrameBound): Builder =
                new Builder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Rows, start, Maybe.empty)))
            inline def frameRange(inline start: FrameBound, inline end: FrameBound): Builder =
                new Builder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Range, start, Maybe(end))))
            inline def frameRange(inline start: FrameBound): Builder =
                new Builder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Range, start, Maybe.empty)))
            inline def frameGroups(inline start: FrameBound, inline end: FrameBound): Builder =
                new Builder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Groups, start, Maybe(end))))
            inline def frameGroups(inline start: FrameBound): Builder =
                new Builder(partitions, orderings, Maybe(WindowFrame(WindowFrame.Kind.Groups, start, Maybe.empty)))

            /** Build the underlying [[WindowSpec]] from the accumulated state. */
            inline def build: WindowSpec = WindowSpec(partitions, orderings, frameOpt)

            // -- standalone window-function terminators --------------------------------
            inline def rowNumber: Term[Long]                 = Windowed(WindowFunction.RowNumber, build)
            inline def rank: Term[Long]                      = Windowed(WindowFunction.Rank, build)
            inline def denseRank: Term[Long]                 = Windowed(WindowFunction.DenseRank, build)
            inline def percentRank: Term[Double]             = Windowed(WindowFunction.PercentRank, build)
            inline def cumeDist: Term[Double]                = Windowed(WindowFunction.CumeDist, build)
            inline def ntile(inline n: Int): Term[Int]       = Windowed(WindowFunction.Ntile(intLit(n)), build)
            inline def ntile(inline n: Term[Int]): Term[Int] = Windowed(WindowFunction.Ntile(n), build)
        end Builder
    end WindowSpec

    final case class WindowFrame(kind: WindowFrame.Kind, start: FrameBound, end: Maybe[FrameBound]) derives CanEqual
    object WindowFrame:
        enum Kind derives CanEqual:
            case Rows, Range, Groups

    sealed trait FrameBound

    object FrameBound:
        given CanEqual[FrameBound, FrameBound] = CanEqual.derived

        case object UnboundedPreceding           extends FrameBound
        case object UnboundedFollowing           extends FrameBound
        case object CurrentRow                   extends FrameBound
        final case class Preceding(n: Term[Int]) extends FrameBound derives CanEqual
        final case class Following(n: Term[Int]) extends FrameBound derives CanEqual

        inline def unboundedPreceding: FrameBound             = UnboundedPreceding
        inline def unboundedFollowing: FrameBound             = UnboundedFollowing
        inline def currentRow: FrameBound                     = CurrentRow
        inline def preceding(inline n: Int): FrameBound       = Preceding(intLit(n))
        inline def preceding(inline n: Term[Int]): FrameBound = Preceding(n)
        inline def following(inline n: Int): FrameBound       = Following(intLit(n))
        inline def following(inline n: Term[Int]): FrameBound = Following(n)
    end FrameBound

    // --- Projection / OrderingSpecs, eager payloads ---

    /** Payload for SELECT terms, resolved eagerly at AST construction time. */
    sealed trait Projection
    object Projection:
        final case class Resolved(terms: Chunk[Term[?]]) extends Projection derives CanEqual

    /** Payload for ORDER BY, resolved eagerly at AST construction time. */
    sealed trait OrderingSpecs
    object OrderingSpecs:
        final case class Resolved(specs: Chunk[OrderSpec]) extends OrderingSpecs derives CanEqual

    // --- Action / Query / From hierarchy ---

    /** DML super-type (INSERT / UPDATE / DELETE). Parallel sibling to [[Query]], both extend [[Executable]] but are otherwise independent. */
    sealed abstract class Action[A] extends Executable[A]

    /** FROM-position contract for [[Select]]: the nodes a projection can select from.
      *
      * Every [[Query]] is a `SelectSource`, since any query can be projected. [[GroupBy]] is the only other member, and the split is
      * deliberate: a grouping becomes a statement only once a projection fixes its SELECT list. `SelectSource` does not extend [[Sql]],
      * so an unprojected `GroupBy` is not an AST node the renderer or the client can consume: none of the query terminals (`run`,
      * `render`, `count`, `union`, subquery positions) accept it. A grouped statement without a SELECT list has no valid SQL form, and
      * no row type either: `Query[T].run` yields `Chunk[T]`, and no grouped statement returns the source row `T`.
      *
      * @tparam A
      *   the source row type the projection selects over
      */
    sealed trait SelectSource[A]

    sealed abstract class Query[A] extends Executable[A] with Term[A] with SelectSource[A]:
        inline def count: Query[Long] = AggregateQuery(this, Aggregate.Count(Maybe.empty, distinct = false))

        inline def exists: Term[Boolean]    = Exists(this)
        inline def notExists: Term[Boolean] = NotExists(this)

        inline def forUpdate: Lock[A]                     = Lock(this, Lock.Mode.Update, Lock.Behavior.Wait, Chunk.empty)
        inline def forUpdate(inline of: String*): Lock[A] = Lock(this, Lock.Mode.Update, Lock.Behavior.Wait, Chunk.from(of))
        inline def forShare: Lock[A]                      = Lock(this, Lock.Mode.Share, Lock.Behavior.Wait, Chunk.empty)
        inline def forShare(inline of: String*): Lock[A]  = Lock(this, Lock.Mode.Share, Lock.Behavior.Wait, Chunk.from(of))
        inline def forUpdateNoWait: Lock[A]               = Lock(this, Lock.Mode.Update, Lock.Behavior.NoWait, Chunk.empty)
        inline def forUpdateSkipLocked: Lock[A]           = Lock(this, Lock.Mode.Update, Lock.Behavior.SkipLocked, Chunk.empty)
        inline def forShareNoWait: Lock[A]                = Lock(this, Lock.Mode.Share, Lock.Behavior.NoWait, Chunk.empty)
        inline def forShareSkipLocked: Lock[A]            = Lock(this, Lock.Mode.Share, Lock.Behavior.SkipLocked, Chunk.empty)

        inline def union(inline other: Query[A]): Query[A]        = SetOp(SetOp.Kind.Union, this, other)
        inline def unionAll(inline other: Query[A]): Query[A]     = SetOp(SetOp.Kind.UnionAll, this, other)
        inline def intersect(inline other: Query[A]): Query[A]    = SetOp(SetOp.Kind.Intersect, this, other)
        inline def intersectAll(inline other: Query[A]): Query[A] = SetOp(SetOp.Kind.IntersectAll, this, other)
        inline def except(inline other: Query[A]): Query[A]       = SetOp(SetOp.Kind.Except, this, other)
        inline def exceptAll(inline other: Query[A]): Query[A]    = SetOp(SetOp.Kind.ExceptAll, this, other)
    end Query

    /** Companion of [[Query]] hosting the `.run` / `.runStatic` / `.runDynamic` extensions.
      *
      * `.run` / `.runStatic` need a `Query[A]` receiver spliced as its full construction tree so the [[kyo.internal.SqlStaticMacro]] can
      * fold the AST at compile time; that requires an `extension [A](inline q: Query[A])` with an inline parameter (an `inline def` on
      * `Query[A]` sees only `Ident(this)` at the splice site, which cannot be folded). Extensions live in this companion (rather than at
      * the top of the `kyo` package) so they are found by implicit search on any `Query[A]` receiver without polluting the top-level
      * namespace.
      */
    object Query:

        extension [A](inline q: Query[A])

            /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time.
              *
              * ==Error types==
              * Aborts with [[SqlException]] (any subtype). The two most common decode-time variants:
              *   - [[SqlDecodeException]], a column value could not be converted to the target Scala type. Check the [[SqlSchema]]
              *     derivation for `A`, ensure columns that can be absent use `Maybe[T]`, and confirm the database schema matches the
              *     query result.
              *   - [[SqlUnsupportedException]], the [[SqlSchema]] decoder called a structural read operation (array element, map entry)
              *     that the backend does not yet implement. Re-derive the schema without the unsupported structural type, or supply a
              *     custom decoder via [[SqlCodec.of]].
              */
            inline def run(using ev: SqlSchema[A], frame: Frame): Chunk[A] < (Abort[SqlException] & DB) =
                ${ kyo.internal.SqlRunMacro.runQueryImpl[A]('q, 'ev, 'frame) }

            /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
            inline def runStatic(using ev: SqlSchema[A], frame: Frame): Chunk[A] < (Abort[SqlException] & DB) =
                ${ kyo.internal.SqlRunMacro.runQueryStaticImpl[A]('q, 'ev, 'frame) }
        end extension

        extension [A](q: Query[A])

            /** Skip the static path entirely and always use the runtime renderer. Non-inline so it can be invoked with a `val` reference.
              *
              * No naming parameter: the columns were resolved when the query value was constructed and the decode is positional by
              * construction, so a run-site [[SqlNaming]] has nothing left to affect.
              */
            def runDynamic(using
                frame: Frame,
                r0: SqlSchema[A]
            ): Chunk[A] < (Abort[SqlException] & DB) =
                DB.state.map { state =>
                    state.client.render(q).map(r =>
                        r.sqlForOrFail(state.client.dialect.id).map(sql =>
                            state.client.internalExecuteQuery[A](sql, r.params, state.config)(using frame, r0)
                        )
                    )
                }
        end extension
    end Query

    sealed abstract class From[T, F] extends Query[T]:
        def columns: Record[F]

        // -- Joins ----------------------------------------------------------
        /** `INNER JOIN`, which returns only matched pairs, so no column on either side becomes a `Maybe`. */
        inline def innerJoin[T2, F2](inline other: From[T2, F2]): Join.OnBuilder[T, F, T2, F2, F & F2] =
            Join.OnBuilder(JoinKind.Inner, this, other)

        /** `LEFT JOIN`, which keeps every left row and leaves the right side's columns absent when nothing matches.
          *
          * Every column of `other` therefore reads back through [[AsMaybeColumns]] as `Maybe`, because an unmatched left row holds no
          * value in any of them. The `on` predicate is unaffected: it is evaluated against real pairs of rows, before any column can go
          * absent, so it still compares the right side's own declared types.
          */
        inline def leftJoin[T2, F2, NF2](inline other: From[T2, F2])(using
            AsMaybeColumns.Aux[F2, NF2]
        ): Join.OnBuilder[T, F, T2, F2, F & NF2] =
            Join.OnBuilder(JoinKind.Left, this, other)

        /** `RIGHT JOIN`, the mirror of [[leftJoin]]: every right row is kept and THIS side is the one left unmatched, so this side's
          * columns become `Maybe`.
          */
        inline def rightJoin[T2, F2, NF](inline other: From[T2, F2])(using
            AsMaybeColumns.Aux[F, NF]
        ): Join.OnBuilder[T, F, T2, F2, NF & F2] =
            Join.OnBuilder(JoinKind.Right, this, other)

        /** `FULL OUTER JOIN`, which keeps unmatched rows from both sides, so BOTH sides' columns become `Maybe`. */
        inline def fullOuterJoin[T2, F2, NF, NF2](inline other: From[T2, F2])(using
            AsMaybeColumns.Aux[F, NF],
            AsMaybeColumns.Aux[F2, NF2]
        ): Join.OnBuilder[T, F, T2, F2, NF & NF2] =
            Join.OnBuilder(JoinKind.FullOuter, this, other)

        /** `CROSS JOIN`, the unconditional product, which leaves no row unmatched, so no column becomes a `Maybe`. */
        inline def crossJoin[T2, F2](inline other: From[T2, F2]): CrossJoin[(T, T2), F & F2] =
            CrossJoin(this, other, columns & other.columns)

        inline def where(inline predicate: Record[F] => Term[Boolean]): Where[T, F] = Where(this, predicate(columns), columns)

        // -- SELECT ---------------------------------------------------------
        inline def select[V](inline projection: Record[F] => Term[V]): Select[T, V, F] =
            Select(this, columns, Projection.Resolved(Chunk(projection(columns))), isDistinct = false)

        @targetName("selectTuple")
        inline def select[Tup <: Tuple, B <: Tuple](inline projection: Record[F] => Tup)(using
            ev: IsTupleOfTerms.Aux[Tup, B]
        ): Select[T, B, F] =
            Select(this, columns, Projection.Resolved(ev.toChunk(projection(columns))), isDistinct = false)

        // -- GROUP BY -------------------------------------------------------
        inline def groupBy[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]] =
            // The key chunk is built at each use rather than bound to a val: a binding in an inline body is a
            // definition, and the inline chain moves it without re-owning it, which fails the tree check when
            // the static-SQL lift reads this query back. `key` is a column selection, so building it twice
            // costs one extra chunk at query-construction time.
            new GroupBy[T, RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]](
                this,
                Chunk(key(columns)),
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]](
                    this,
                    Chunk(key(columns))
                ),
                Maybe.empty,
                GroupBy.Kind.Plain
            )
        end groupBy

        @targetName("groupByTuple")
        inline def groupBy[Tup <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfTerms[Tup]
        ): GroupBy[T, RewriteGrouped[FT, ColumnsToFields[Tup]]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy[T, RewriteGrouped[FT, ColumnsToFields[Tup]]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteGrouped[FT, ColumnsToFields[Tup]]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Plain
            )
        end groupBy

        // -- GROUP BY ROLLUP ------------------------------------------------

        /** `GROUP BY ROLLUP (key)`, which emits the detail rows and then a subtotal row per prefix of the key list.
          *
          * The key is OPTIONAL in the post-groupBy view, because a subtotal row holds no value for every key it is not grouping by. So
          * projecting `view.<key>` yields a `Term[Maybe[V]]`, and a key that was already optional stays at one level of absence (see
          * [[AsMaybe]]).
          */
        inline def groupByRollup[N <: String & Singleton, V, NV, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT],
            AsMaybe.Aux[V, NV]
        ): GroupBy[T, RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy[T, RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Rollup
            )
        end groupByRollup

        /** `GROUP BY ROLLUP (keys*)`. Every key is optional in the post-groupBy view; see the single-key overload. */
        @targetName("groupByRollupTuple")
        inline def groupByRollup[Tup <: Tuple, NF <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfColumns.Aux[Tup, NF]
        ): GroupBy[T, RewriteSuperAggregated[FT, NF]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy[T, RewriteSuperAggregated[FT, NF]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteSuperAggregated[FT, NF]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Rollup
            )
        end groupByRollup

        // -- GROUP BY CUBE -------------------------------------------------

        /** `GROUP BY CUBE (key)`, which emits the detail rows and then a super-aggregate row per subset of the key list.
          *
          * The key is OPTIONAL in the post-groupBy view, for the same reason as [[groupByRollup]]: a super-aggregate row holds no value
          * for every key its own subset omits.
          */
        inline def groupByCube[N <: String & Singleton, V, NV, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT],
            AsMaybe.Aux[V, NV]
        ): GroupBy[T, RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy[T, RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Cube
            )
        end groupByCube

        /** `GROUP BY CUBE (keys*)`. Every key is optional in the post-groupBy view; see the single-key overload. */
        @targetName("groupByCubeTuple")
        inline def groupByCube[Tup <: Tuple, NF <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfColumns.Aux[Tup, NF]
        ): GroupBy[T, RewriteSuperAggregated[FT, NF]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy[T, RewriteSuperAggregated[FT, NF]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteSuperAggregated[FT, NF]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Cube
            )
        end groupByCube

        // -- GROUP BY GROUPING SETS ----------------------------------------

        /** `GROUP BY GROUPING SETS (...)`, the escape hatch that takes the grouping sets explicitly.
          *
          * Unlike [[groupByRollup]] and [[groupByCube]], the post-groupBy view here is the source's own (`columns`), so no field's type is
          * rewritten. Rows where a field is not in the active grouping set hold no value for it, exactly as they do under rollup and cube,
          * so a projection of such a field has to be optional by the caller's own doing: declare the field `Maybe` in the row type, or
          * project it through a term that is already a `Maybe`.
          */
        inline def groupByGroupingSets(inline sets: Record[F] => Chunk[Chunk[Term[?]]]): GroupBy[T, F] =
            val nested: Chunk[Chunk[Term[?]]] = sets(columns)
            val flatKeys: Chunk[Term[?]]      = Chunk.from(nested.flatten.distinct)
            new GroupBy(this, flatKeys, columns, Maybe.empty, GroupBy.Kind.GroupingSets(nested))
        end groupByGroupingSets

        inline def orderBy[A](inline spec: Record[F] => A)(using arg: OrderArg[A]): OrderBy[T] =
            OrderBy(this, OrderingSpecs.Resolved(arg.toChunk(spec(columns))))

        // Whole-table aggregates. Every one but COUNT is a `Maybe`: `SELECT SUM(x) FROM t` over an input that contributes no rows
        // returns exactly one row holding no value, so a total result type would make an empty table a decode failure. COUNT is
        // the exception the standard grants, returning 0. The widths come from SqlNumeric for the same reason as the window forms.
        inline def count[V](inline column: Record[F] => Term[V]): Query[Long] =
            AggregateQuery(this, Aggregate.Count(Maybe(column(columns)), distinct = false))
        inline def countDistinct[V](inline column: Record[F] => Term[V]): Query[Long] =
            AggregateQuery(this, Aggregate.Count(Maybe(column(columns)), distinct = true))
        inline def sum[V, S](inline column: Record[F] => Term[V])(using
            w: SqlNumeric.Aux[V, S, ?, ?],
            n: AsMaybe[S]
        ): Query[n.Out] =
            AggregateQuery(this, Aggregate.Sum[n.Out](column(columns), distinct = false))
        inline def sumDistinct[V, S](inline column: Record[F] => Term[V])(using
            w: SqlNumeric.Aux[V, S, ?, ?],
            n: AsMaybe[S]
        ): Query[n.Out] =
            AggregateQuery(this, Aggregate.Sum[n.Out](column(columns), distinct = true))
        inline def avg[V, G](inline column: Record[F] => Term[V])(using
            w: SqlNumeric.Aux[V, ?, G, ?],
            n: AsMaybe[G]
        ): Query[n.Out] =
            AggregateQuery(this, Aggregate.Avg[n.Out](column(columns), distinct = false))
        inline def avgDistinct[V, G](inline column: Record[F] => Term[V])(using
            w: SqlNumeric.Aux[V, ?, G, ?],
            n: AsMaybe[G]
        ): Query[n.Out] =
            AggregateQuery(this, Aggregate.Avg[n.Out](column(columns), distinct = true))
        inline def min[V](inline column: Record[F] => Term[V])(using n: AsMaybe[V]): Query[n.Out] =
            AggregateQuery(this, Aggregate.Min[n.Out](column(columns)))
        inline def max[V](inline column: Record[F] => Term[V])(using n: AsMaybe[V]): Query[n.Out] =
            AggregateQuery(this, Aggregate.Max[n.Out](column(columns)))

        inline def limit(inline n: Int): Limit[T]                     = limit(n, 0)
        inline def limit(inline n: Int, inline offset: Int): Limit[T] = Limit(this, n, offset)
    end From

    /** `columnNames` is `T`'s case-class field names in declaration order (from `SqlMacros.columnNames[T]`). The renderer emits these as
      * the explicit `SELECT` projection for an implicit-projection (`SELECT *`-shaped) query, a literal `Chunk[String]` that lifts via
      * `FromExpr.derived` with zero reflection. It is the reliable Schema-field-order source: the `columns` `Record`'s `Dict` does not
      * preserve declaration order (it reverses for ≤8 fields and is hash-ordered above), so the renderer must not derive order from it.
      */
    final case class Table[T, F](
        columns: Record[F],
        alias: String,
        tableName: String,
        columnNames: Chunk[String]
    ) extends From[T, F]:

        /** The same table under an explicit alias, `Sql.from[Invoice]("i")`: a fresh construction, so the record re-keys on `N` and
          * an overridden table name does not survive (say it beside the alias when both are wanted). The body is a copy, not a
          * delegation: transparent inline methods cannot delegate the construction without the record's refined type widening away.
          */
        transparent inline def apply[N <: String & Singleton](alias: N)(using f: Fields[T]) =
            val cols = kyo.internal.SqlAstInternal.buildColumns[T, N](alias)
            Table[T, RecordF[cols.type]](
                cols,
                alias,
                kyo.internal.SqlMacros.tableName[T],
                kyo.internal.SqlMacros.columnNames[T]
            )
        end apply

        /** An explicit alias and an explicitly named table, overriding the `T`-derived default. The name is a literal, folded directly
          * into the static render (again a body copy, for the same widening reason, keeping the name's literal position).
          */
        transparent inline def apply[N <: String & Singleton](alias: N, inline tableName: String)(using f: Fields[T]) =
            val cols = kyo.internal.SqlAstInternal.buildColumns[T, N](alias)
            Table[T, RecordF[cols.type]](
                cols,
                alias,
                tableName,
                kyo.internal.SqlMacros.columnNames[T]
            )
        end apply
    end Table

    final case class Nested[T, F](
        columns: Record[F],
        source: Query[?],
        alias: String,
        columnNames: Chunk[String]
    ) extends From[T, F]

    object Nested:
        final class Builder[T]:
            transparent inline def apply[N <: String & Singleton](alias: N, query: Query[?])(using f: Fields[T]) =
                val cols = kyo.internal.SqlAstInternal.buildColumns[T, N](alias)
                Nested[T, RecordF[cols.type]](cols, query, alias, kyo.internal.SqlMacros.columnNames[T])
            end apply
        end Builder
    end Nested

    final case class Lateral[T, F](
        columns: Record[F],
        source: Query[?],
        alias: String,
        columnNames: Chunk[String]
    ) extends From[T, F]

    object Lateral:
        final class Builder[T]:
            transparent inline def apply[N <: String & Singleton](alias: N, query: Query[?])(using f: Fields[T]) =
                val cols = kyo.internal.SqlAstInternal.buildColumns[T, N](alias)
                Lateral[T, RecordF[cols.type]](cols, query, alias, kyo.internal.SqlMacros.columnNames[T])
            end apply
        end Builder
    end Lateral

    /** `(VALUES (…), (…)) AS alias` query source. `rows` is stored as decomposed pure data, outer `Chunk` = rows, inner `Chunk` = one
      * [[Sql.BoundValue]] per column in declaration order, so the AST lifts via `FromExpr.derived` with zero reflection. The row type `T`
      * is retained for builder-side type-safety only.
      */
    final case class ValuesFrom[T, F](
        columns: Record[F],
        rows: Chunk[Chunk[BoundValue[?]]],
        alias: String,
        columnNames: Chunk[String]
    ) extends From[T, F]

    object ValuesFrom:
        final class Builder[T]:
            transparent inline def apply[N <: String & Singleton](alias: N, inline rows: T*)(using
                m: Mirror.ProductOf[T],
                f: Fields[T]
            ) =
                val cols = kyo.internal.SqlAstInternal.buildColumns[T, N](alias)
                ValuesFrom[T, RecordF[cols.type]](
                    cols,
                    kyo.internal.SqlMacros.rowValues[T](rows),
                    alias,
                    kyo.internal.SqlMacros.columnNames[T]
                )
            end apply
        end Builder
    end ValuesFrom

    enum JoinKind derives CanEqual:
        case Inner, Left, Right, FullOuter

    final case class Join[T, F](
        kind: JoinKind,
        left: From[?, ?],
        right: From[?, ?],
        predicate: Term[Boolean],
        columns: Record[F]
    ) extends From[T, F]

    object Join:
        /** Partially-applied join awaiting its `ON` predicate.
          *
          * `OF` is the joined view's field type, which is NOT always `F1 & F2`: an outer join leaves the side that can go unmatched with
          * no value in its columns, so that side's columns arrive as `Maybe`. The join methods on [[From]] compute `OF` per kind (see
          * [[From.leftJoin]]), which is why it is a parameter here rather than an intersection formed in `on`.
          *
          * The predicate still sees `Record[F1 & F2]`, the two sides at their declared types. `ON` is evaluated against real rows, before
          * any column can go absent, so `j.a.id == j.b.aId` compares two columns at their declared types even under a `fullOuterJoin`.
          */
        final case class OnBuilder[T1, F1, T2, F2, OF](
            kind: JoinKind,
            left: From[T1, F1],
            right: From[T2, F2]
        ):
            inline def on(inline predicate: Record[F1 & F2] => Term[Boolean]): Join[(T1, T2), OF] =
                val merged = left.columns & right.columns
                // `Record` is phantom in its field type at runtime (the dict holds the same `Column` values either way), so the joined
                // view differs from `merged` only in what the compiler knows about it. Same seam and same helper as
                // `SqlGroupedView.buildGroupedView`, which narrows the post-groupBy view for the same reason.
                Join(kind, left, right, predicate(merged), kyo.internal.macros.MacroSupport.narrowPhantom[Record[OF]](merged))
            end on
        end OnBuilder
    end Join

    final case class CrossJoin[T, F](
        left: From[?, ?],
        right: From[?, ?],
        columns: Record[F]
    ) extends From[T, F]

    final case class Where[T, F](
        sql: From[T, F],
        predicate: Term[Boolean],
        columns: Record[F]
    ) extends Query[T]:

        inline def select[V](inline projection: Record[F] => Term[V]): Select[T, V, F] =
            Select(this, columns, Projection.Resolved(Chunk(projection(columns))), isDistinct = false)

        @targetName("selectTuple")
        inline def select[Tup <: Tuple, B <: Tuple](inline projection: Record[F] => Tup)(using
            ev: IsTupleOfTerms.Aux[Tup, B]
        ): Select[T, B, F] =
            Select(this, columns, Projection.Resolved(ev.toChunk(projection(columns))), isDistinct = false)

        inline def groupBy[N <: String & Singleton, V, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT]
        ): GroupBy[T, RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]] =
            // The key chunk is built at each use rather than bound to a val: a binding in an inline body is a
            // definition, and the inline chain moves it without re-owning it, which fails the tree check when
            // the static-SQL lift reads this query back. `key` is a column selection, so building it twice
            // costs one extra chunk at query-construction time.
            new GroupBy[T, RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]](
                this,
                Chunk(key(columns)),
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteGrouped[FT, (N ~ Unit) *: EmptyTuple]](
                    this,
                    Chunk(key(columns))
                ),
                Maybe.empty,
                GroupBy.Kind.Plain
            )
        end groupBy

        @targetName("groupByTuple")
        inline def groupBy[Tup <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfTerms[Tup]
        ): GroupBy[T, RewriteGrouped[FT, ColumnsToFields[Tup]]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy[T, RewriteGrouped[FT, ColumnsToFields[Tup]]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteGrouped[FT, ColumnsToFields[Tup]]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Plain
            )
        end groupBy

        // -- GROUP BY ROLLUP ------------------------------------------------

        /** `GROUP BY ROLLUP (key)`, which emits the detail rows and then a subtotal row per prefix of the key list.
          *
          * The key is OPTIONAL in the post-groupBy view, because a subtotal row holds no value for every key it is not grouping by. So
          * projecting `view.<key>` yields a `Term[Maybe[V]]`, and a key that was already optional stays at one level of absence (see
          * [[AsMaybe]]).
          */
        inline def groupByRollup[N <: String & Singleton, V, NV, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT],
            AsMaybe.Aux[V, NV]
        ): GroupBy[T, RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy[T, RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Rollup
            )
        end groupByRollup

        /** `GROUP BY ROLLUP (keys*)`. Every key is optional in the post-groupBy view; see the single-key overload. */
        @targetName("groupByRollupTuple")
        inline def groupByRollup[Tup <: Tuple, NF <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfColumns.Aux[Tup, NF]
        ): GroupBy[T, RewriteSuperAggregated[FT, NF]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy[T, RewriteSuperAggregated[FT, NF]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteSuperAggregated[FT, NF]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Rollup
            )
        end groupByRollup

        // -- GROUP BY CUBE -------------------------------------------------

        /** `GROUP BY CUBE (key)`, which emits the detail rows and then a super-aggregate row per subset of the key list.
          *
          * The key is OPTIONAL in the post-groupBy view, for the same reason as [[groupByRollup]]: a super-aggregate row holds no value
          * for every key its own subset omits.
          */
        inline def groupByCube[N <: String & Singleton, V, NV, FT <: Tuple](inline key: Record[F] => Column[N, V])(using
            Fields.Aux[T, FT],
            AsMaybe.Aux[V, NV]
        ): GroupBy[T, RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]] =
            val ks = Chunk(key(columns))
            new GroupBy[T, RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteSuperAggregated[FT, (N ~ NV) *: EmptyTuple]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Cube
            )
        end groupByCube

        /** `GROUP BY CUBE (keys*)`. Every key is optional in the post-groupBy view; see the single-key overload. */
        @targetName("groupByCubeTuple")
        inline def groupByCube[Tup <: Tuple, NF <: Tuple, FT <: Tuple](inline keys: Record[F] => Tup)(using
            f: Fields.Aux[T, FT],
            ev: IsTupleOfColumns.Aux[Tup, NF]
        ): GroupBy[T, RewriteSuperAggregated[FT, NF]] =
            val ks = ev.toChunk(keys(columns))
            new GroupBy[T, RewriteSuperAggregated[FT, NF]](
                this,
                ks,
                kyo.internal.SqlGroupedView.buildGroupedView[RewriteSuperAggregated[FT, NF]](this, ks),
                Maybe.empty,
                GroupBy.Kind.Cube
            )
        end groupByCube

        // -- GROUP BY GROUPING SETS ----------------------------------------

        /** `GROUP BY GROUPING SETS (...)`, the escape hatch that takes the grouping sets explicitly.
          *
          * Unlike [[groupByRollup]] and [[groupByCube]], the post-groupBy view here is the source's own (`columns`), so no field's type is
          * rewritten. Rows where a field is not in the active grouping set hold no value for it, exactly as they do under rollup and cube,
          * so a projection of such a field has to be optional by the caller's own doing: declare the field `Maybe` in the row type, or
          * project it through a term that is already a `Maybe`.
          */
        inline def groupByGroupingSets(inline sets: Record[F] => Chunk[Chunk[Term[?]]]): GroupBy[T, F] =
            val nested: Chunk[Chunk[Term[?]]] = sets(columns)
            val flatKeys: Chunk[Term[?]]      = Chunk.from(nested.flatten.distinct)
            new GroupBy(this, flatKeys, columns, Maybe.empty, GroupBy.Kind.GroupingSets(nested))
        end groupByGroupingSets

        inline def orderBy[A](inline spec: Record[F] => A)(using arg: OrderArg[A]): OrderBy[T] =
            OrderBy(this, OrderingSpecs.Resolved(arg.toChunk(spec(columns))))

        // Whole-table aggregates over the filtered set. Optional for the same reason as [[From]]'s, and more visibly so here: a
        // predicate that matches nothing is the ordinary way to reach `SELECT SUM(x) FROM t WHERE ...` returning one row with no value.
        inline def count[V](inline column: Record[F] => Term[V]): Query[Long] =
            AggregateQuery(this, Aggregate.Count(Maybe(column(columns)), distinct = false))
        inline def countDistinct[V](inline column: Record[F] => Term[V]): Query[Long] =
            AggregateQuery(this, Aggregate.Count(Maybe(column(columns)), distinct = true))
        inline def sum[V, S](inline column: Record[F] => Term[V])(using
            w: SqlNumeric.Aux[V, S, ?, ?],
            n: AsMaybe[S]
        ): Query[n.Out] =
            AggregateQuery(this, Aggregate.Sum[n.Out](column(columns), distinct = false))
        inline def sumDistinct[V, S](inline column: Record[F] => Term[V])(using
            w: SqlNumeric.Aux[V, S, ?, ?],
            n: AsMaybe[S]
        ): Query[n.Out] =
            AggregateQuery(this, Aggregate.Sum[n.Out](column(columns), distinct = true))
        inline def avg[V, G](inline column: Record[F] => Term[V])(using
            w: SqlNumeric.Aux[V, ?, G, ?],
            n: AsMaybe[G]
        ): Query[n.Out] =
            AggregateQuery(this, Aggregate.Avg[n.Out](column(columns), distinct = false))
        inline def avgDistinct[V, G](inline column: Record[F] => Term[V])(using
            w: SqlNumeric.Aux[V, ?, G, ?],
            n: AsMaybe[G]
        ): Query[n.Out] =
            AggregateQuery(this, Aggregate.Avg[n.Out](column(columns), distinct = true))
        inline def min[V](inline column: Record[F] => Term[V])(using n: AsMaybe[V]): Query[n.Out] =
            AggregateQuery(this, Aggregate.Min[n.Out](column(columns)))
        inline def max[V](inline column: Record[F] => Term[V])(using n: AsMaybe[V]): Query[n.Out] =
            AggregateQuery(this, Aggregate.Max[n.Out](column(columns)))

        inline def limit(inline n: Int): Limit[T]                     = limit(n, 0)
        inline def limit(inline n: Int, inline offset: Int): Limit[T] = Limit(this, n, offset)
    end Where

    /** GroupBy[T, F'], F' is the post-groupBy record's F (grouped → `GroupedColumn`, ungrouped → `GroupTerm`).
      *
      * The `view: Record[F]` is materialised eagerly at the `groupBy[…]` construction site (see [[internal.SqlGroupedView]]) so the chain
      * methods (`having` / `select`) apply the user lambda inline and store the resulting `Term` payloads. The AST is pure data, no
      * lambdas in case-class field positions.
      *
      * A `GroupBy` is a [[SelectSource]], not a [[Query]]: a grouping only becomes a statement once `select` fixes its SELECT list, so
      * an unprojected grouping cannot be rendered, executed, or placed in any query position. Ordering and limiting therefore live on
      * the [[Select]] the projection produces.
      */
    final case class GroupBy[T, F](
        source: Query[T],
        keys: Chunk[Term[?]],
        view: Record[F],
        havingTerm: Maybe[Term[Boolean]],
        kind: GroupBy.Kind
    ) extends SelectSource[T]:

        inline def having(inline p: Record[F] => Term[Boolean]): GroupBy[T, F] =
            copy(havingTerm = Maybe(p(view)))

        inline def select[V](inline p: Record[F] => Term[V]): Select[T, V, F] =
            Select(this, view, Projection.Resolved(Chunk(p(view))), isDistinct = false)

        @targetName("selectTuple")
        inline def select[Tup <: Tuple, B <: Tuple](inline p: Record[F] => Tup)(using ev: IsTupleOfTerms.Aux[Tup, B]): Select[T, B, F] =
            Select(this, view, Projection.Resolved(ev.toChunk(p(view))), isDistinct = false)
    end GroupBy

    object GroupBy:
        /** Grouping kind, controls how the renderer emits the GROUP BY clause.
          *   - `Plain` → `GROUP BY a, b` (standard, all backends).
          *   - `Rollup` → `GROUP BY ROLLUP (a, b)` on Postgres; `GROUP BY a, b WITH ROLLUP` on MySQL.
          *   - `Cube` → `GROUP BY CUBE (a, b)` on Postgres; `GROUP BY a, b WITH CUBE` on MySQL (MySQL 8.0+).
          *   - `GroupingSets(sets)` → `GROUP BY GROUPING SETS ((a, b), (c), ())` (Postgres; MySQL 8.0+ supports same syntax). Each inner
          *     `Chunk[Term[?]]` is one grouping set; an empty inner chunk renders as `()` (the grand-total grouping).
          */
        sealed trait Kind derives CanEqual
        object Kind:
            case object Plain                                          extends Kind
            case object Rollup                                         extends Kind
            case object Cube                                           extends Kind
            final case class GroupingSets(sets: Chunk[Chunk[Term[?]]]) extends Kind
        end Kind
    end GroupBy

    /** Post-groupBy ungrouped field, only aggregate methods, each returning the type its own aggregate returns.
      *
      * These are the one aggregate family that is NOT optional for a total operand, and the reason is structural: a group exists
      * because at least one row fell into it, so the input a grouped aggregate sees is never empty. That is what separates these from
      * [[From.sum]] and friends, where the whole table can contribute nothing and the server answers with one row holding no value.
      *
      * Absence still arrives from the operand. `SUM` and `AVG` skip the rows that hold no value, so a group in which no row holds one has
      * nothing to aggregate and the answer is itself absent: that case is carried by [[SqlNumeric]] mapping a `Maybe[A]` operand to a
      * `Maybe` result, and by `V` already being the `Maybe` for [[min]] and [[max]].
      */
    sealed trait GroupTerm[V]:
        private[kyo] def underlying: Column[?, V]
        final inline def sum(using n: SqlNumeric[V]): Term[n.Sum]         = Aggregate.Sum[n.Sum](underlying, distinct = false)
        final inline def sumDistinct(using n: SqlNumeric[V]): Term[n.Sum] = Aggregate.Sum[n.Sum](underlying, distinct = true)
        final inline def avg(using n: SqlNumeric[V]): Term[n.Avg]         = Aggregate.Avg[n.Avg](underlying, distinct = false)
        final inline def avgDistinct(using n: SqlNumeric[V]): Term[n.Avg] = Aggregate.Avg[n.Avg](underlying, distinct = true)
        final inline def min: Term[V]                                     = Aggregate.Min[V](underlying)
        final inline def max: Term[V]                                     = Aggregate.Max[V](underlying)
        final inline def count: Term[Long]                                = Aggregate.Count(Maybe(underlying), distinct = false)
        final inline def countDistinct: Term[Long]                        = Aggregate.Count(Maybe(underlying), distinct = true)
    end GroupTerm

    final case class UngroupedView[V](private[kyo] val underlying: Column[?, V]) extends GroupTerm[V] derives CanEqual

    final case class GroupedColumn[N <: String & Singleton, V](column: Column[N, V])
        extends Term[V] with GroupTerm[V] derives CanEqual:
        private[kyo] def underlying: Column[?, V] = column

    final case class OrderBy[A](sql: Query[A], specs: OrderingSpecs) extends Query[A] derives CanEqual:
        inline def limit(inline n: Int): Limit[A]                     = limit(n, 0)
        inline def limit(inline n: Int, inline offset: Int): Limit[A] = Limit(this, n, offset)

        /** Coerces the row type to a case class, as [[Select.to]] does. Carried through here so the combinator order stops being
          * load-bearing: `.select(...).orderBy(...).to[Row]` reads the way it is written, rather than compiling only when `.to` sits
          * immediately after `.select`.
          */
        inline def to[B2](using m: Mirror.ProductOf[B2] { type MirroredElemTypes = A & Tuple }): OrderBy[B2] =
            OrderBy[B2](sql.asInstanceOf[Query[B2]], specs)
    end OrderBy

    final case class Limit[A](sql: Query[A], n: Int, offset: Int) extends Query[A] derives CanEqual:
        /** Coerces the row type to a case class, as [[Select.to]] does. See [[OrderBy.to]]. */
        inline def to[B2](using m: Mirror.ProductOf[B2] { type MirroredElemTypes = A & Tuple }): Limit[B2] =
            Limit[B2](sql.asInstanceOf[Query[B2]], n, offset)
    end Limit

    final case class Lock[A](
        sql: Query[A],
        mode: Lock.Mode,
        behavior: Lock.Behavior,
        ofTables: Chunk[String]
    ) extends Query[A] derives CanEqual
    object Lock:
        enum Mode derives CanEqual:
            case Update, Share
        enum Behavior derives CanEqual:
            case Wait, NoWait, SkipLocked
    end Lock

    final case class SetOp[A](kind: SetOp.Kind, left: Query[A], right: Query[A]) extends Query[A] derives CanEqual
    object SetOp:
        enum Kind derives CanEqual:
            case Union, UnionAll, Intersect, IntersectAll, Except, ExceptAll

    final case class With[A](ctes: Chunk[CommonTable[?]], body: Query[A])          extends Query[A] derives CanEqual
    final case class WithRecursive[A](ctes: Chunk[CommonTable[?]], body: Query[A]) extends Query[A] derives CanEqual

    final case class CommonTable[B](name: String, query: Query[B]) derives CanEqual

    final case class AggregateQuery[A](source: Query[?], agg: Aggregate.Call[A]) extends Query[A] derives CanEqual

    /** A SELECT clause. `terms` is a [[Projection]] (resolved or deferred).
      *
      * Carries `columns`, the source's own column record, for the clauses that follow a projection in SQL: `ORDER BY` names columns of the
      * source, which may include columns the projection leaves out, so the record has to survive the projection for [[orderBy]] to take a
      * lambda at all. It is the same record the source node holds, exactly as [[Where]] carries it forward from its own source.
      *
      * `sql` is a [[SelectSource]] rather than a [[Query]] because a [[GroupBy]] is a legal FROM position for a projection while not
      * being a query of its own; the projection is what turns a grouping into a statement.
      */
    final case class Select[A, B, F](sql: SelectSource[A], columns: Record[F], terms: Projection, isDistinct: Boolean)
        extends Query[B] derives CanEqual:
        inline def distinct: Select[A, B, F] = copy(isDistinct = true)

        /** Coerces the row type to a case class `B2` whose field types match the projected tuple positionally. Only meaningful for
          * tuple-formed selects, where `B` is the value-type tuple from `IsTupleOfTerms.Out`.
          */
        inline def to[B2](using m: Mirror.ProductOf[B2] { type MirroredElemTypes = B & Tuple }): Select[A, B2, F] =
            Select[A, B2, F](sql, columns, terms, isDistinct)

        /** `ORDER BY` over the projected rows, ordering by any term built from the source's columns.
          *
          * The rows are already the projection's, so this yields `OrderBy[B]` and `.limit` follows it. Ordering by a column the projection
          * omits is legal and useful for a plain projection, and both servers reject it when the projection is `distinct`, which is the one
          * combination to keep in mind here.
          */
        inline def orderBy[Arg](inline spec: Record[F] => Arg)(using arg: OrderArg[Arg]): OrderBy[B] =
            OrderBy(this, OrderingSpecs.Resolved(arg.toChunk(spec(columns))))

        /** `LIMIT n` over the projected rows. */
        inline def limit(inline n: Int): Limit[B] = limit(n, 0)

        /** `LIMIT n OFFSET offset` over the projected rows. */
        inline def limit(inline n: Int, inline offset: Int): Limit[B] = Limit(this, n, offset)
    end Select

    // --- INSERT / UPDATE / DELETE ---

    /** INSERT statement.
      *
      * @param overrides
      *   Per-column assignments that replace what the source would otherwise send for those columns, appended by [[Insert.overriding]]. The
      *   one that matters is `_.id := Sql.default`, which puts the `DEFAULT` keyword in a generated key's cell.
      * @param autoKey
      *   SQL column name of the auto-incrementing primary key, when one is detected on the target table, resolved through the same renames
      *   and naming strategy as [[columnNames]] and quoted by the renderer rather than here. `Maybe.Absent` when no auto-key column is
      *   present. The Postgres renderer auto-appends `RETURNING <autoKey>` when this is `Present`. MySQL ignores this field and reads
      *   `last_insert_id` from the OK packet instead.
      *
      * Auto-key detection uses the "first-column-if-Long" fallback (see `Sql.insert`). Because the rule reads the Scala type and not the DDL,
      * detecting a key says nothing about whether the server generates it, which is why `values` sends the column and `Sql.insert`'s scaladoc
      * documents the two spellings that ask the server for the key instead.
      */
    final case class Insert[T, F](
        columns: Record[F],
        tableName: String,
        columnNames: Chunk[String],
        source: Insert.Source[T, F],
        overrides: Chunk[SetSpec[?, ?]],
        onConflict: Maybe[Insert.OnConflict[F]],
        autoKey: Maybe[String],
        returning: Maybe[Chunk[Column[?, ?]]]
    ) extends Action[Long]:
        /** Replaces what this INSERT sends for the named columns, leaving the rest of the source untouched.
          *
          * The use it exists for is a generated key: `values(user).overriding(_.id := Sql.default)` keeps the row the caller has in hand and
          * puts the `DEFAULT` keyword in the key's cell, so the server assigns it. Any assignable value works, so a column can equally be
          * overridden with an expression the row cannot carry.
          *
          * Applies to the two sources that emit a cell per column, `values` and `partialValues`. A `fromSelect` insert takes its values from
          * a query's projection, where `DEFAULT` is not a legal expression and there is no cell to replace, so an override there fails the
          * render rather than being dropped.
          */
        inline def overriding(inline specs: (Record[F] => SetSpec[? <: String, ?])*): Insert[T, F] =
            copy(overrides = overrides ++ Chunk.from(specs.map(_(columns))))
        inline def onConflictDoNothing(inline targets: (Record[F] => Column[? <: String, ?])*): Insert[T, F] =
            Insert[T, F](
                columns,
                tableName,
                columnNames,
                source,
                overrides,
                Maybe(Insert.OnConflict.DoNothing(Chunk.from(targets.map(_(columns))))),
                autoKey,
                returning
            )
        inline def onConflictDoUpdate(inline targets: (Record[F] => Column[? <: String, ?])*): Insert.OnConflict.DoUpdateBuilder[T, F] =
            Insert.OnConflict.DoUpdateBuilder(this, Chunk.from(targets.map(_(columns))), Maybe.empty)

        /** Ask for a column of the rows this INSERT wrote, typed as that column.
          *
          * The rows are what the statement then answers, in place of the affected-row count and generated key an INSERT reports
          * otherwise. Asking for the key column is how a caller reads a server-assigned key as a value rather than through
          * [[kyo.SqlClient.InsertOutcome]], and the renderer already prefers an explicit RETURNING over the one it appends for a detected
          * auto-key, so the two do not both emit.
          */
        inline def returning[N <: String & Singleton, V](inline col: Record[F] => Column[N, V]): Insert.Returning[T, F, V] =
            Insert.Returning[T, F, V](copy(returning = Maybe(Chunk(col(columns)))))

        /** Ask for several columns of the rows this INSERT wrote, typed as the tuple they were asked for. */
        @targetName("returningTuple")
        inline def returning[Tup <: Tuple, Vs <: Tuple](inline cols: Record[F] => Tup)(using
            ev: IsTupleOfReturned.Aux[Tup, Vs]
        ): Insert.Returning[T, F, Vs] =
            Insert.Returning[T, F, Vs](copy(returning = Maybe(ev.toChunk(cols(columns)))))
    end Insert

    object Insert:
        sealed abstract class Source[T, F]

        /** `VALUES (…), (…)` rows for an INSERT, stored as decomposed pure data: outer `Chunk` = rows, inner `Chunk` = one
          * [[Sql.BoundValue]] per column in declaration order. The row type `T` is retained for builder-side type-safety only, it is
          * phantom at the node. Decomposing the rows (rather than storing raw `T` instances) keeps the AST pure data so `FromExpr.derived`
          * lifts it with zero reflection.
          */
        final case class Values[T, F](rows: Chunk[Chunk[BoundValue[?]]])                    extends Source[T, F]
        final case class PartialValues[T, F](sets: Chunk[SetSpec[?, ?]])                    extends Source[T, F] derives CanEqual
        final case class FromSelect[T, F, B](columns: Chunk[Column[?, ?]], query: Query[B]) extends Source[T, F] derives CanEqual

        sealed trait OnConflict[F]
        object OnConflict:
            final case class DoNothing[F](targets: Chunk[Column[?, ?]]) extends OnConflict[F] derives CanEqual
            final case class DoUpdate[F](
                targets: Chunk[Column[?, ?]],
                sets: Chunk[SetSpec[?, ?]],
                where: Maybe[Term[Boolean]]
            ) extends OnConflict[F] derives CanEqual

            final case class DoUpdateBuilder[T, F](
                insert: Insert[T, F],
                targets: Chunk[Column[?, ?]],
                whereClause: Maybe[Term[Boolean]]
            ):
                inline def where(inline predicate: Record[F] => Term[Boolean]): DoUpdateBuilder[T, F] =
                    copy(whereClause = Maybe(predicate(insert.columns)))
                inline def apply(inline sets: (Record[F] => SetSpec[? <: String, ?])*): Insert[T, F] =
                    Insert[T, F](
                        insert.columns,
                        insert.tableName,
                        insert.columnNames,
                        insert.source,
                        insert.overrides,
                        Maybe(Insert.OnConflict.DoUpdate(targets, Chunk.from(sets.map(_(insert.columns))), whereClause)),
                        insert.autoKey,
                        insert.returning
                    )
            end DoUpdateBuilder
        end OnConflict

        extension [T, F](inline ins: Insert[T, F])

            /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time. */
            inline def run(using frame: Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
                ${ kyo.internal.SqlRunMacro.runInsertImpl[T, F]('ins, 'frame) }

            /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
            inline def runStatic(using Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
                ${ kyo.internal.SqlRunMacro.runInsertStaticImpl[T, F]('ins) }
        end extension

        extension [T, F](ins: Insert[T, F])

            /** Skip the static path entirely and always use the runtime renderer. Non-inline so it can be invoked with a `val` reference. */
            def runDynamic(using frame: Frame): SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
                DB.state.map { state =>
                    state.client.render(ins).map(r =>
                        r.sqlForOrFail(state.client.dialect.id).map(sql => state.client.internalExecuteInsert(sql, r.params, state.config))
                    )
                }
        end extension

        final case class Builder[T, F](
            columns: Record[F],
            tableName: String,
            columnNames: Chunk[String],
            autoKey: Maybe[String] = Maybe.empty
        ):
            /** Sends one row per `T`, every column in declaration order, including a detected auto-key column.
              *
              * A generated key is asked for with [[Insert.overriding]] or `partialValues`; `Sql.insert`'s scaladoc has the reason the column
              * is sent rather than dropped and what a server does with an explicit value in it.
              */
            inline def values(inline rows: T*): Insert[T, F] =
                Insert[T, F](
                    columns,
                    tableName,
                    columnNames,
                    Insert.Values(kyo.internal.SqlMacros.rowValues[T](rows)),
                    Chunk.empty,
                    Maybe.empty,
                    autoKey,
                    Maybe.empty
                )
            @targetName("valuesSeq")
            inline def values(inline rows: Seq[T]): Insert[T, F] =
                Insert[T, F](
                    columns,
                    tableName,
                    columnNames,
                    Insert.Values(kyo.internal.SqlMacros.rowValues[T](rows)),
                    Chunk.empty,
                    Maybe.empty,
                    autoKey,
                    Maybe.empty
                )
            inline def partialValues(inline specs: (Record[F] => SetSpec[? <: String, ?])*): Insert[T, F] =
                Insert[T, F](
                    columns,
                    tableName,
                    columnNames,
                    Insert.PartialValues(Chunk.from(specs.map(_(columns)))),
                    Chunk.empty,
                    Maybe.empty,
                    autoKey,
                    Maybe.empty
                )
            inline def fromSelect[B](inline cols: (Record[F] => Column[? <: String, ?])*)(inline query: Query[B]): Insert[T, F] =
                Insert[T, F](
                    columns,
                    tableName,
                    columnNames,
                    Insert.FromSelect(Chunk.from(cols.map(_(columns))), query),
                    Chunk.empty,
                    Maybe.empty,
                    autoKey,
                    Maybe.empty
                )
        end Builder

        /** An INSERT that answers the rows it wrote rather than how many.
          *
          * The same wrapper [[Sql.Update.Returning]] is, for the same reason: the RETURNING clause lives on the statement and the
          * renderers already emit it, so what this adds is the type of what comes back. It replaces the [[kyo.SqlClient.InsertOutcome]]
          * an INSERT answers otherwise, because a caller who named the columns has said what they want back, and the count of a values
          * list is one the caller already holds.
          */
        final case class Returning[T, F, A](statement: Insert[T, F]) derives CanEqual

        object Returning:
            extension [T, F, A](inline ret: Returning[T, F, A])
                /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time. */
                inline def run(using ev: SqlSchema[A], frame: Frame): Chunk[A] < (Abort[SqlException] & DB) =
                    ${ kyo.internal.SqlRunMacro.runInsertReturningImpl[T, F, A]('ret, 'ev, 'frame) }

                /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
                inline def runStatic(using ev: SqlSchema[A], frame: Frame): Chunk[A] < (Abort[SqlException] & DB) =
                    ${ kyo.internal.SqlRunMacro.runInsertReturningStaticImpl[T, F, A]('ret, 'ev, 'frame) }
            end extension

            extension [T, F, A](ret: Returning[T, F, A])
                /** Skip the static path entirely and always use the runtime renderer. */
                def runDynamic(using frame: Frame, evidence: SqlSchema[A]): Chunk[A] < (Abort[SqlException] & DB) =
                    DB.state.map { state =>
                        state.client.render(ret.statement).map(r =>
                            r.sqlForOrFail(state.client.dialect.id).map(sql =>
                                state.client.internalExecuteQuery[A](sql, r.params, state.config)
                            )
                        )
                    }
            end extension
        end Returning
    end Insert

    final case class Update[T, F](
        columns: Record[F],
        tableName: String,
        sets: Chunk[SetSpec[?, ?]],
        whereClause: Maybe[Term[Boolean]],
        returning: Maybe[Chunk[Column[?, ?]]]
    ) extends Action[Long]

    /** Companion of [[Update]] hosting the `.run` / `.runStatic` / `.runDynamic` extensions (see [[Query$]] for the rationale). */
    object Update:

        extension [T, F](inline upd: Update[T, F])

            /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time. */
            inline def run(using frame: Frame): Long < (Abort[SqlException] & DB) =
                ${ kyo.internal.SqlRunMacro.runUpdateImpl[T, F]('upd, 'frame) }

            /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
            inline def runStatic(using Frame): Long < (Abort[SqlException] & DB) =
                ${ kyo.internal.SqlRunMacro.runUpdateStaticImpl[T, F]('upd) }
        end extension

        extension [T, F](upd: Update[T, F])

            /** Skip the static path entirely and always use the runtime renderer. Non-inline so it can be invoked with a `val` reference. */
            def runDynamic(using Frame): Long < (Abort[SqlException] & DB) =
                DB.state.map { state =>
                    state.client.render(upd).map(r =>
                        r.sqlForOrFail(state.client.dialect.id).map(sql => state.client.internalExecuteUpdate(sql, r.params, state.config))
                    )
                }
        end extension

        /** The accumulating half of an UPDATE: the table's columns, its name, and the assignments named so far.
          *
          * `sets` has no default value on purpose. A default reaches the compile-time render as a call to the synthetic accessor the
          * compiler generates for it, whose body that render cannot see, and one unreadable field is enough to make the whole statement
          * unfoldable. Written at the two construction sites instead.
          */
        final case class Builder[T, F](
            columns: Record[F],
            tableName: String,
            sets: Chunk[SetSpec[?, ?]]
        ):
            inline def set(inline specs: (Record[F] => SetSpec[? <: String, ?])*): Builder[T, F] =
                copy(sets = sets ++ Chunk.from(specs.map(_(columns))))
            inline def where(inline predicate: Record[F] => Term[Boolean]): Update[T, F] =
                Update[T, F](columns, tableName, sets, Maybe(predicate(columns)), Maybe.empty)
            inline def build: Update[T, F] = Update[T, F](columns, tableName, sets, Maybe.empty, Maybe.empty)

            /** Ask for the rows the statement changed, one column.
              *
              * The result is typed by what was asked for, so `.run` answers those rows rather than a count: a builder method whose result
              * a caller cannot reach is worse than not having the method.
              */
            inline def returning[N <: String & Singleton, V](inline col: Record[F] => Column[N, V]): ReturningBuilder[T, F, V] =
                ReturningBuilder(columns, tableName, sets, Chunk(col(columns)))

            /** Ask for the rows the statement changed, several columns, typed as the tuple they were asked for. */
            @targetName("returningTuple")
            inline def returning[Tup <: Tuple, Vs <: Tuple](inline cols: Record[F] => Tup)(using
                ev: IsTupleOfReturned.Aux[Tup, Vs]
            ): ReturningBuilder[T, F, Vs] =
                ReturningBuilder(columns, tableName, sets, ev.toChunk(cols(columns)))
        end Builder

        final case class ReturningBuilder[T, F, A](
            columns: Record[F],
            tableName: String,
            sets: Chunk[SetSpec[?, ?]],
            returningCols: Chunk[Column[?, ?]]
        ):
            inline def where(inline predicate: Record[F] => Term[Boolean]): Returning[T, F, A] =
                Returning[T, F, A](Update[T, F](columns, tableName, sets, Maybe(predicate(columns)), Maybe(returningCols)))
            inline def build: Returning[T, F, A] =
                Returning[T, F, A](Update[T, F](columns, tableName, sets, Maybe.empty, Maybe(returningCols)))
        end ReturningBuilder

        /** An UPDATE that answers the rows it changed rather than how many.
          *
          * A thin wrapper over the statement, not a node of its own: the RETURNING clause lives on the statement and the renderers already
          * emit it, so what this adds is the type of what comes back, which is what the terminals need in order to decode. A flavor with
          * no RETURNING clause refuses the statement when it is rendered, rather than answering an empty result.
          */
        final case class Returning[T, F, A](statement: Update[T, F]) derives CanEqual

        object Returning:
            extension [T, F, A](inline ret: Returning[T, F, A])
                /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time. */
                inline def run(using ev: SqlSchema[A], frame: Frame): Chunk[A] < (Abort[SqlException] & DB) =
                    ${ kyo.internal.SqlRunMacro.runReturningImpl[T, F, A]('ret, 'ev, 'frame) }

                /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
                inline def runStatic(using ev: SqlSchema[A], frame: Frame): Chunk[A] < (Abort[SqlException] & DB) =
                    ${ kyo.internal.SqlRunMacro.runReturningStaticImpl[T, F, A]('ret, 'ev, 'frame) }
            end extension

            extension [T, F, A](ret: Returning[T, F, A])
                /** Skip the static path entirely and always use the runtime renderer. */
                def runDynamic(using frame: Frame, evidence: SqlSchema[A]): Chunk[A] < (Abort[SqlException] & DB) =
                    DB.state.map { state =>
                        state.client.render(ret.statement).map(r =>
                            r.sqlForOrFail(state.client.dialect.id).map(sql =>
                                state.client.internalExecuteQuery[A](sql, r.params, state.config)
                            )
                        )
                    }
            end extension
        end Returning
    end Update

    final case class Delete[T, F](
        columns: Record[F],
        tableName: String,
        whereClause: Maybe[Term[Boolean]],
        returning: Maybe[Chunk[Column[?, ?]]]
    ) extends Action[Long]

    /** Companion of [[Delete]] hosting the `.run` / `.runStatic` / `.runDynamic` extensions (see [[Query$]] for the rationale). */
    object Delete:

        extension [T, F](inline del: Delete[T, F])

            /** Try the static-emission path; fall back to the runtime renderer if the AST is not reducible at compile time. */
            inline def run(using frame: Frame): Long < (Abort[SqlException] & DB) =
                ${ kyo.internal.SqlRunMacro.runDeleteImpl[T, F]('del, 'frame) }

            /** Requires compile-time AST reduction; produces a compile error if the AST is not reducible. */
            inline def runStatic(using Frame): Long < (Abort[SqlException] & DB) =
                ${ kyo.internal.SqlRunMacro.runDeleteStaticImpl[T, F]('del) }
        end extension

        extension [T, F](del: Delete[T, F])

            /** Skip the static path entirely and always use the runtime renderer. Non-inline so it can be invoked with a `val` reference. */
            def runDynamic(using Frame): Long < (Abort[SqlException] & DB) =
                DB.state.map { state =>
                    state.client.render(del).map(r =>
                        r.sqlForOrFail(state.client.dialect.id).map(sql => state.client.internalExecuteUpdate(sql, r.params, state.config))
                    )
                }
        end extension

        final case class Builder[T, F](
            columns: Record[F],
            tableName: String
        ):
            inline def where(inline predicate: Record[F] => Term[Boolean]): Delete[T, F] =
                Delete[T, F](columns, tableName, Maybe(predicate(columns)), Maybe.empty)
            inline def build: Delete[T, F] = Delete[T, F](columns, tableName, Maybe.empty, Maybe.empty)

            /** Ask for the rows the statement removed, one column. See [[Update.Builder.returning]]. */
            inline def returning[N <: String & Singleton, V](inline col: Record[F] => Column[N, V]): ReturningBuilder[T, F, V] =
                ReturningBuilder(columns, tableName, Chunk(col(columns)))

            /** Ask for the rows the statement removed, several columns, typed as the tuple they were asked for. */
            @targetName("returningTuple")
            inline def returning[Tup <: Tuple, Vs <: Tuple](inline cols: Record[F] => Tup)(using
                ev: IsTupleOfReturned.Aux[Tup, Vs]
            ): ReturningBuilder[T, F, Vs] =
                ReturningBuilder(columns, tableName, ev.toChunk(cols(columns)))
        end Builder

        final case class ReturningBuilder[T, F, A](
            columns: Record[F],
            tableName: String,
            returningCols: Chunk[Column[?, ?]]
        ):
            inline def where(inline predicate: Record[F] => Term[Boolean]): Returning[T, F, A] =
                Returning[T, F, A](Delete[T, F](columns, tableName, Maybe(predicate(columns)), Maybe(returningCols)))
            inline def build: Returning[T, F, A] =
                Returning[T, F, A](Delete[T, F](columns, tableName, Maybe.empty, Maybe(returningCols)))
        end ReturningBuilder

        /** A DELETE that answers the rows it removed rather than how many. See [[Update.Returning]]. */
        final case class Returning[T, F, A](statement: Delete[T, F]) derives CanEqual

        object Returning:
            extension [T, F, A](inline ret: Returning[T, F, A])
                inline def run(using ev: SqlSchema[A], frame: Frame): Chunk[A] < (Abort[SqlException] & DB) =
                    ${ kyo.internal.SqlRunMacro.runDeleteReturningImpl[T, F, A]('ret, 'ev, 'frame) }

                inline def runStatic(using ev: SqlSchema[A], frame: Frame): Chunk[A] < (Abort[SqlException] & DB) =
                    ${ kyo.internal.SqlRunMacro.runDeleteReturningStaticImpl[T, F, A]('ret, 'ev, 'frame) }
            end extension

            extension [T, F, A](ret: Returning[T, F, A])
                def runDynamic(using frame: Frame, evidence: SqlSchema[A]): Chunk[A] < (Abort[SqlException] & DB) =
                    DB.state.map { state =>
                        state.client.render(ret.statement).map(r =>
                            r.sqlForOrFail(state.client.dialect.id).map(sql =>
                                state.client.internalExecuteQuery[A](sql, r.params, state.config)
                            )
                        )
                    }
            end extension
        end Returning
    end Delete

    /** What a column can be assigned: an expression, or the column's own declared default.
      *
      * Every [[Term]] is a `SetValue`, so the ordinary assignment needs nothing from this type. The alternative, [[SetValue.Default]], is
      * the reason it exists: `DEFAULT` is a keyword the server accepts in an assignment and nowhere else, so it is not a `Term` and cannot
      * reach a `WHERE` clause, a projection, or a function argument, each of which would render SQL no server parses.
      */
    sealed trait SetValue[A]

    object SetValue:

        /** The column's declared default, rendered as the `DEFAULT` keyword.
          *
          * Written as [[kyo.Sql.default]], and reachable only through [[Sql.Column.:=]]. On a generated-key column this is what asks the
          * server for the next key: `Sql.insert[User].values(user).overriding(_.id := Sql.default)` sends `DEFAULT` in that one cell, so the
          * sequence advances and the key that comes back is the server's.
          */
        final case class Default[A]() extends SetValue[A] derives CanEqual
    end SetValue

    /** One column assignment, `column = value`. */
    final case class SetSpec[N <: String & Singleton, V](column: Column[N, V], value: SetValue[V]) derives CanEqual

    // --- CASE WHEN builder ---

    object Case:

        final case class WhenBuilder(predicate: Term[Boolean]) derives CanEqual:

            inline def to[A](inline value: Term[A]): Builder[A] = Builder(Chunk((predicate, value)))

            @targetName("toRaw")
            inline def to[A](inline value: A)(using c: SqlSchema.Column[A]): Builder[A] = to(lit(value, c))
        end WhenBuilder

        final case class Builder[A](whens: Chunk[(Term[Boolean], Term[A])]) derives CanEqual:

            inline def when(inline predicate: Term[Boolean]): WhenContinuation[A] = WhenContinuation(whens, predicate)

            inline def otherwise(inline value: Term[A]): Term[A] = CaseExpr(whens, value)

            @targetName("otherwiseRaw")
            inline def otherwise(inline value: A)(using c: SqlSchema.Column[A]): Term[A] = otherwise(lit(value, c))

            inline def otherwiseAbsent: Term[Maybe[A]] = CaseExprMaybe(whens)
        end Builder

        final case class WhenContinuation[A](whens: Chunk[(Term[Boolean], Term[A])], predicate: Term[Boolean]) derives CanEqual:

            inline def to(inline value: Term[A]): Builder[A] = Builder(whens :+ ((predicate, value)))

            @targetName("toRaw")
            inline def to(inline value: A)(using c: SqlSchema.Column[A]): Builder[A] = to(lit(value, c))
        end WhenContinuation
    end Case

    // --- Typeclasses ---

    /** An operand type SQL can do arithmetic and aggregation on, carrying the Scala type each of those returns.
      *
      * The three type members exist because a server does not hand back the operand's own type. `SUM` over an `INTEGER` is `bigint` on
      * PostgreSQL and `DECIMAL` on MySQL; `AVG` over any exact type is `numeric` / `DECIMAL` on both, because the average of `30` and `31`
      * is not an integer in any flavor; and `/` over two integers is a truncated integer on PostgreSQL and a `DECIMAL` on MySQL. So each
      * member names the one Scala type both servers' answers decode into without loss:
      *
      *   - [[Sum]] widens: `Int` to `Long` (PostgreSQL's `int8`, and MySQL's `DECIMAL` narrows into it exactly), `Long` to `BigDecimal`
      *     (PostgreSQL returns `numeric` for exactly the overflow reason), and `Float` to `Double` (MySQL sums approximate values in double
      *     precision, and PostgreSQL's `real` widens into it losslessly).
      *   - [[Avg]] is `BigDecimal` for every exact operand and `Double` for the approximate ones.
      *   - [[Div]] is the fractional quotient: `BigDecimal` for exact operands, `Double` for approximate ones. `Term./` types its result
      *     here, and [[Sql.Term.divideTruncating]] is the spelling that keeps the truncated integral quotient.
      *
      * A [[Maybe]] operand maps to a [[Maybe]] result at the same width, so an optional column's aggregate is optional and nothing nests.
      */
    sealed trait SqlNumeric[A]:
        /** The Scala type `SUM` over an `A` returns. */
        type Sum

        /** The Scala type `AVG` over an `A` returns. */
        type Avg

        /** The Scala type the fractional quotient of two `A`s has. */
        type Div
    end SqlNumeric

    object SqlNumeric:

        /** Refinement alias exposing all three result types, so a DSL method can bind them as inferred type parameters. */
        type Aux[A, S, V, D] =
            SqlNumeric[A]:
                type Sum = S
                type Avg = V
                type Div = D

        private def instance[A, S, V, D]: Aux[A, S, V, D] =
            new SqlNumeric[A]:
                type Sum = S
                type Avg = V
                type Div = D

        // Every type a single column can carry as a number, which is what makes an aggregate reachable from any of them. `Short` and
        // `Byte` are stored as a two-byte integer and widen to `Long` under SUM exactly as `Int` does; `BigInt` is stored as an exact
        // decimal and aggregates as one, like `BigDecimal`. Leaving them out is what made "total units per order" unreachable on a
        // `smallint` column, since a grouped view has no `cast` to widen through either.
        given int: Aux[Int, Long, BigDecimal, BigDecimal]                     = instance
        given long: Aux[Long, BigDecimal, BigDecimal, BigDecimal]             = instance
        given short: Aux[Short, Long, BigDecimal, BigDecimal]                 = instance
        given byte: Aux[Byte, Long, BigDecimal, BigDecimal]                   = instance
        given float: Aux[Float, Double, Double, Double]                       = instance
        given double: Aux[Double, Double, Double, Double]                     = instance
        given bigInt: Aux[BigInt, BigDecimal, BigDecimal, BigDecimal]         = instance
        given bigDecimal: Aux[BigDecimal, BigDecimal, BigDecimal, BigDecimal] = instance

        given maybe[A, S, V, D](using Aux[A, S, V, D]): Aux[Maybe[A], Maybe[S], Maybe[V], Maybe[D]] = instance
    end SqlNumeric

    /** An operand type stored as a SQL integer, so its division truncates on at least one flavor.
      *
      * Gates [[Sql.Term.divideTruncating]], whose whole purpose is that truncation, and selects which division PostgreSQL has to cast:
      * `int4 / int4` truncates there while `numeric / numeric` and `float8 / float8` do not, so only an integral `/` needs the cast that
      * reaches the fractional quotient the DSL types.
      *
      * The instance list is the integral subset of [[SqlNumeric]]'s: the four types stored as a SQL integer. `BigInt` is not one of them
      * even though it is exact, because it is stored as a decimal, whose division does not truncate on either flavor.
      */
    sealed trait SqlIntegral[A]
    object SqlIntegral:
        given int: SqlIntegral[Int]                                 = new SqlIntegral[Int] {}
        given long: SqlIntegral[Long]                               = new SqlIntegral[Long] {}
        given short: SqlIntegral[Short]                             = new SqlIntegral[Short] {}
        given byte: SqlIntegral[Byte]                               = new SqlIntegral[Byte] {}
        given maybe[A](using SqlIntegral[A]): SqlIntegral[Maybe[A]] = new SqlIntegral[Maybe[A]] {}
    end SqlIntegral

    /** Lifts `A` to the type a possibly-absent `A` has, without nesting: `Out` is `Maybe[Int]` for `Int`, and `Maybe[Int]` again for
      * `Maybe[Int]`.
      *
      * Idempotence is the semantics, not a convenience. There is only one kind of absence: a column that can already be absent and that
      * also arrives absent because its group contributed no rows is still one level of absence, and there is no second level to
      * represent. `Maybe[Maybe[A]]` would also be undecodable, since the outer read consumes the column and the inner one has none left.
      *
      * A typeclass rather than a match type because `Maybe` is opaque outside its own module. A `case Maybe[a] => Maybe[a]` arm does match a
      * `Maybe` selector, but for any other selector the compiler cannot show the selector disjoint from `Maybe[a]` (an opaque type's upper
      * bound is `Any`), so reduction stalls before it reaches the fall-through and even `Out[Int]` fails to reduce. Implicit resolution
      * prioritises by specificity instead, which is what the two instances below rely on.
      */
    sealed trait AsMaybe[A]:
        /** `A` made optional. */
        type Out

    object AsMaybe extends AsMaybeLowPriority:

        /** Refinement alias exposing [[AsMaybe.Out]], so a DSL method can bind it as an inferred type parameter. */
        type Aux[A, O] =
            AsMaybe[A]:
                type Out = O

        /** An already-optional type lifts to itself. Declared here rather than in [[AsMaybeLowPriority]] so it outranks
          * [[AsMaybeLowPriority.plain]], which also applies at `A = Maybe[X]` and would produce the nested type.
          */
        given alreadyMaybe[A]: Aux[Maybe[A], Maybe[A]] = AsMaybe.instance
    end AsMaybe

    sealed trait AsMaybeLowPriority:
        private[kyo] def instance[A, O]: AsMaybe.Aux[A, O] =
            new AsMaybe[A]:
                type Out = O

        given plain[A]: AsMaybe.Aux[A, Maybe[A]] = instance
    end AsMaybeLowPriority

    /** Lifts every column of a query source's field type `F` through [[AsMaybe]], producing the field type that source has once an outer
      * join can leave it unmatched.
      *
      * `F` here is a [[From]]'s own field type, so it is an intersection of `alias ~ Record[columnFields]` entries: one per source for a
      * table, several for a source that is itself a join. `Out` is the same shape with every leaf `Column[n, v]` retyped to
      * `Column[n, AsMaybe[v]]`, so `j.b.budget` after a `leftJoin` is a `Term[Maybe[Long]]` and can be decoded, absence-tested and
      * `coalesce`d. Idempotent by construction: [[AsMaybe.alreadyMaybe]] outranks the general instance, so a column that was already
      * `Maybe` stays at one level of absence rather than nesting.
      *
      * A typeclass rather than a match type for two independent reasons, each fatal on its own. A match type cannot decompose the
      * intersection (there is no pattern for `A & B`), and it cannot express the lift to `Maybe` itself, for the reason recorded on
      * [[AsMaybe]]. So the decomposition borrows [[Fields]]' tuple form and the lift recurses over that tuple.
      */
    sealed trait AsMaybeColumns[F]:
        /** `F` with every column made optional. */
        type Out

    object AsMaybeColumns:
        /** Refinement alias exposing [[AsMaybeColumns.Out]], so a join method can bind it as an inferred type parameter. */
        type Aux[F, O] =
            AsMaybeColumns[F]:
                type Out = O

        private def instance[F, O]: Aux[F, O] =
            new AsMaybeColumns[F]:
                type Out = O

        given derive[F, FT <: Tuple, NFT <: Tuple](using
            Fields.Aux[F, FT],
            PerField.Aux[FT, NFT]
        ): Aux[F, Fields.Join[NFT]] = instance

        /** The inductive half, over the tuple form [[Fields]] reifies `F` into.
          *
          * Two recursive arms because a [[From]]'s field type nests exactly one level: the outer entry is `alias ~ Record[columnFields]`
          * and the leaves inside it are `name ~ Column[name, value]`. [[nested]] walks into the inner record, `column` applies the lift.
          */
        sealed trait PerField[T <: Tuple]:
            type Out <: Tuple

        object PerField:
            type Aux[T <: Tuple, O <: Tuple] =
                PerField[T]:
                    type Out = O

            private def instance[T <: Tuple, O <: Tuple]: Aux[T, O] =
                new PerField[T]:
                    type Out = O

            given empty: Aux[EmptyTuple, EmptyTuple] = instance

            given column[N <: String & Singleton, V, NV, Rest <: Tuple, ORest <: Tuple](using
                AsMaybe.Aux[V, NV],
                Aux[Rest, ORest]
            ): Aux[(N ~ Column[N, V]) *: Rest, (N ~ Column[N, NV]) *: ORest] = instance

            given nested[N <: String & Singleton, In, IT <: Tuple, OIT <: Tuple, Rest <: Tuple, ORest <: Tuple](using
                Fields.Aux[In, IT],
                Aux[IT, OIT],
                Aux[Rest, ORest]
            ): Aux[(N ~ Record[In]) *: Rest, (N ~ Record[Fields.Join[OIT]]) *: ORest] = instance
        end PerField
    end AsMaybeColumns

    /** Inductive proof that `Tup` is a tuple whose every element is some subtype of `Term[?]` (e.g. `Column[N, V]` extends `Term[V]`).
      * Provides safe element-by-element extraction into a `Chunk[Term[?]]` and exposes the value-type tuple via `Out` (used by
      * `Select.to[B2]`).
      */
    sealed trait IsTupleOfTerms[Tup <: Tuple]:
        type Out <: Tuple
        def toChunk(t: Tup): Chunk[Term[?]]
    object IsTupleOfTerms:
        type Aux[Tup <: Tuple, O <: Tuple] = IsTupleOfTerms[Tup] { type Out = O }

        given empty: Aux[EmptyTuple, EmptyTuple] = new IsTupleOfTerms[EmptyTuple]:
            type Out = EmptyTuple
            def toChunk(t: EmptyTuple): Chunk[Term[?]] = Chunk.empty

        given cons[H, Tail <: Tuple, OTail <: Tuple, TermH <: Term[H]](
            using rest: Aux[Tail, OTail]
        ): Aux[TermH *: Tail, H *: OTail] = new IsTupleOfTerms[TermH *: Tail]:
            type Out = H *: OTail
            def toChunk(t: TermH *: Tail): Chunk[Term[?]] = t.head +: rest.toChunk(t.tail)
    end IsTupleOfTerms

    /** Inductive proof that `Tup` is a tuple of [[Column]]s, exposing the tuple of their value types.
      *
      * What a `RETURNING` clause needs from a projection: the clause itself names columns, which every flavor that has one renders as bare
      * identifiers, and the rows that come back decode at those columns' value types. [[Values]] is that decode type, so asking for two
      * columns produces rows of the pair.
      */
    sealed trait IsTupleOfReturned[Tup <: Tuple]:
        type Values <: Tuple
        def toChunk(t: Tup): Chunk[Column[?, ?]]

    object IsTupleOfReturned:
        type Aux[Tup <: Tuple, Vs <: Tuple] = IsTupleOfReturned[Tup] { type Values = Vs }

        given empty: Aux[EmptyTuple, EmptyTuple] = new IsTupleOfReturned[EmptyTuple]:
            type Values = EmptyTuple
            def toChunk(t: EmptyTuple): Chunk[Column[?, ?]] = Chunk.empty

        given cons[N <: String & Singleton, V, Tail <: Tuple, VTail <: Tuple](
            using rest: Aux[Tail, VTail]
        ): Aux[Column[N, V] *: Tail, V *: VTail] = new IsTupleOfReturned[Column[N, V] *: Tail]:
            type Values = V *: VTail
            def toChunk(t: Column[N, V] *: Tail): Chunk[Column[?, ?]] = t.head +: rest.toChunk(t.tail)
    end IsTupleOfReturned

    /** Inductive proof that `Tup` is a tuple of [[Column]]s, exposing as [[MaybeFields]] the `Name ~ Value` field tuple those columns
      * form with every value type lifted through [[AsMaybe]].
      *
      * Drives the tuple-keyed [[From.groupByRollup]] and [[From.groupByCube]], where each key can arrive absent in a super-aggregate row.
      * The lift to `Maybe` happens here, once per key, because [[AsMaybe]] is a typeclass and the type-level rewrite that consumes the
      * result ([[RewriteSuperAggregated]]) is a match type that cannot summon one; it reads the already-lifted type back out of this tuple
      * by name.
      *
      * `Column` rather than `Term` is required because the post-`groupBy` view is rebuilt from the source's columns by name, so a key that is
      * not a column has nothing in the view to rewrite.
      */
    sealed trait IsTupleOfColumns[Tup <: Tuple]:
        type MaybeFields <: Tuple
        def toChunk(t: Tup): Chunk[Term[?]]

    object IsTupleOfColumns:
        type Aux[Tup <: Tuple, NF <: Tuple] = IsTupleOfColumns[Tup] { type MaybeFields = NF }

        given empty: Aux[EmptyTuple, EmptyTuple] = new IsTupleOfColumns[EmptyTuple]:
            type MaybeFields = EmptyTuple
            def toChunk(t: EmptyTuple): Chunk[Term[?]] = Chunk.empty

        given cons[N <: String & Singleton, V, NV, Tail <: Tuple, NFTail <: Tuple](
            using
            lift: AsMaybe.Aux[V, NV],
            rest: Aux[Tail, NFTail]
        ): Aux[Column[N, V] *: Tail, (N ~ NV) *: NFTail] = new IsTupleOfColumns[Column[N, V] *: Tail]:
            type MaybeFields = (N ~ NV) *: NFTail
            def toChunk(t: Column[N, V] *: Tail): Chunk[Term[?]] = t.head +: rest.toChunk(t.tail)
    end IsTupleOfColumns

    /** Disambiguator for `orderBy`: lets one overload accept either a single `OrderSpec` or a tuple of `OrderSpec`s without overload
      * ambiguity. Inductive given derivation prevents silent drops.
      */
    sealed trait OrderArg[A]:
        def toChunk(a: A): Chunk[OrderSpec]

    object OrderArg:
        given single: OrderArg[OrderSpec] with
            def toChunk(a: OrderSpec): Chunk[OrderSpec] = Chunk(a)

        /** A bare term orders ascending, which is what SQL does with a bare column and what `.asc` spells explicitly.
          *
          * Without this the DSL was stricter than the language it mirrors: `orderBy(r => r.customers.id)` did not compile, and the error
          * named `OrderArg` rather than the `.asc` it wanted. The explicit spellings stay, and the absent-placement ones
          * (`ascAbsentFirst` and its siblings) are the reason to reach for them.
          */
        given term[A <: Term[?]]: OrderArg[A] with
            def toChunk(a: A): Chunk[OrderSpec] =
                Chunk(OrderSpec(a, OrderSpec.Direction.Asc, OrderSpec.AbsentPlacement.Default))

        given empty: OrderArg[EmptyTuple] with
            def toChunk(a: EmptyTuple): Chunk[OrderSpec] = Chunk.empty

        // The head is whatever the element itself is an OrderArg for, so a tuple mixes bare terms and explicit specs.
        given cons[H, T <: Tuple](using head: OrderArg[H], rest: OrderArg[T]): OrderArg[H *: T] with
            def toChunk(a: H *: T): Chunk[OrderSpec] = head.toChunk(a.head) ++ rest.toChunk(a.tail)
    end OrderArg

    // --- Internal type machinery and helpers ---

    type ColumnFields[T <: Tuple] = T match
        case EmptyTuple      => Any
        case (n ~ v) *: rest => (n ~ Column[n & String, v]) & ColumnFields[rest]

    type RecordF[R] = R match
        case Record[f] => f

    type ExtractTermValueTypes[T <: Tuple] <: Tuple = T match
        case EmptyTuple      => EmptyTuple
        case Term[v] *: rest => v *: ExtractTermValueTypes[rest]

    type ColumnsToFields[T <: Tuple] <: Tuple = T match
        case EmptyTuple           => EmptyTuple
        case Column[n, ?] *: rest => (n ~ Unit) *: ColumnsToFields[rest]

    type GroupedG[GroupedFs <: Tuple] =
        [n <: String & Singleton, v] =>> Fields.IfHasName[GroupedFs, n, GroupedColumn[n, v], GroupTerm[v]]

    type RewriteGrouped[FT <: Tuple, GroupedFs <: Tuple] =
        Fields.Join[Tuple.Map[FT, Record.~.MapNamedValue[GroupedG[GroupedFs]]]]

    /** Post-`groupBy` leaf mapping for a super-aggregate grouping (`ROLLUP`, `CUBE`).
      *
      * Differs from [[GroupedG]] in one place: a grouped key's value type is read out of `GroupedFs` rather than taken from the source field,
      * because `GROUP BY ROLLUP (a, b)` emits subtotal rows holding no value in the keys it is not grouping by, so every key is optional.
      * The lifted type is computed at the `groupByRollup` call site (see [[AsMaybe]] and [[IsTupleOfColumns]]) and travels here as the
      * marker tuple's payload, which is why the payload is looked up instead of rewritten in place.
      */
    type SuperAggregatedG[GroupedFs <: Tuple] =
        [n <: String & Singleton, v] =>> Fields.IfHasName[
            GroupedFs,
            n,
            GroupedColumn[n, Record.FieldValue[GroupedFs, n]],
            GroupTerm[v]
        ]

    type RewriteSuperAggregated[FT <: Tuple, GroupedFs <: Tuple] =
        Fields.Join[Tuple.Map[FT, Record.~.MapNamedValue[SuperAggregatedG[GroupedFs]]]]

    /** Builds a [[Literal]] capturing the value's Scala type name from the concrete type at this inline construction site, so the render
      * diagnostics can name the type where no runtime tag exists.
      */
    private[kyo] inline def lit[A](value: A, s: SqlSchema.Column[A]): Literal[A] =
        Literal(value, s, kyo.internal.SqlMacros.typeNameOf[A])

    /** The label is the variant name, stored through `w.string` as a plain `TEXT` column. The unknown-label read aborts with
      * [[SqlDecodeSumTypeUnknownLabelException]].
      */
    private def enumTextImpl[E](labels: Chunk[String], singletons: Chunk[Any], m: Mirror.SumOf[E]): SqlSchema.Column[E] =
        SqlSchema.of[E](
            write = (v, w) => w.string(labels(m.ordinal(v))),
            read = r =>
                val label = r.string()
                val ord   = labels.indexOf(label)
                if ord < 0 then throw SqlDecodeSumTypeUnknownLabelException(label, labels)(using r.frame)
                // Erasure boundary: singletons was built from the same Mirror.SumOf, so ord indexes a value of E.
                singletons(ord).asInstanceOf[E]
        )
    end enumTextImpl

    /** Collects the compile-time string literal at each position of `Labels` into an ordered list. */
    private inline def enumTextLabels[Labels <: Tuple]: List[String] =
        inline erasedValue[Labels] match
            case _: EmptyTuple => Nil
            case _: (h *: t)   => constValue[h & String] :: enumTextLabels[t]

    /** Collects the [[ValueOf]]-materialised singleton instance for each variant type, ordered by ordinal. */
    private inline def enumTextSingletons[Types <: Tuple]: List[Any] =
        inline erasedValue[Types] match
            case _: EmptyTuple => Nil
            case _: (h *: t)   => summonInline[ValueOf[h]].value :: enumTextSingletons[t]

    private[kyo] inline def intLit(inline n: Int): Term[Int] = lit(n, SqlSchema.int)

    private[kyo] inline def oneInt: Term[Int] = intLit(1)

end Sql

/** Top-level `sql"..."` interpolator, builds a composable [[kyo.Sql.Fragment]] from a string interpolation. Interpolated arguments are
  * classified at compile time: subtypes of `Term[?]` are embedded inline (allowing column / sub-query references), and other types are
  * bound as parameters via their `SqlSchema.Column[A]`.
  */
extension (sc: StringContext)
    inline def sql(inline args: Any*): Sql.Fragment[Any] =
        ${ kyo.internal.SqlFragmentMacro.sqlImpl('sc, 'args) }
end extension
