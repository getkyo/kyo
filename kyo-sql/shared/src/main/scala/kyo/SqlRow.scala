package kyo

import kyo.*
import scala.annotation.publicInBinary
import scala.util.control.NonFatal

/** A single row from a query result.
  *
  * Holds the raw bytes of each column together with the neutral column metadata the backend reported, plus the [[SqlRow.Codec]] that knows
  * how to turn those bytes into values. Column values are whatever wire form the backend chose for the query that produced them; core never
  * interprets them, it hands them to the codec.
  *
  * Rows are constructed by backends, never by user code. Read a typed value with [[decode]], or the raw bytes with `column`.
  *
  * @param values
  *   one entry per column; [[Maybe.Absent]] represents SQL NULL
  * @param columns
  *   column metadata in the same order as [[values]]
  * @param codec
  *   the backend's decoder for these bytes
  */
final class SqlRow(
    val values: Chunk[Maybe[Span[Byte]]],
    val columns: Chunk[SqlRow.Column],
    private[kyo] val codec: SqlRow.Codec
):

    /** Number of columns in this row. */
    def size: Int = values.size

    /** The column names, in order. */
    def columnNames: Chunk[String] = columns.map(_.name)

    /** What kind of value the column at `idx` carries, in the neutral vocabulary both backends map their own types onto.
      *
      * The reader for a result set nobody typed: a tool running user-written SQL knows a column's name and its bytes, and this is what
      * says whether those bytes are a number, an instant, or text, without the caller learning either backend's type tags.
      * [[SqlRow.ColumnKind.Unknown]] for a type the backend has no neutral kind for and for a row assembled without server metadata.
      */
    def columnKind(idx: Int): SqlRow.ColumnKind =
        if idx < 0 || idx >= columns.size then SqlRow.ColumnKind.Unknown
        else codec.columnKind(columns(idx).typeToken)

    /** What kind of value the column named `name` carries. [[SqlRow.ColumnKind.Unknown]] when the row has no such column. */
    def columnKind(name: String): SqlRow.ColumnKind =
        columnKind(columns.indexWhere(_.name == name))

    /** The backend's own name for the type of the column at `idx`: `int4` and `timestamptz` on PostgreSQL, `BIGINT` and `DATETIME` on
      * MySQL. Absent for a type token the backend does not recognise, which is what a row assembled without server metadata carries.
      *
      * For a decision a caller acts on, prefer [[columnKind]], which is the same fact in a vocabulary that does not change with the engine.
      * This is for showing a human which type the server reported.
      */
    def columnTypeName(idx: Int): Maybe[String] =
        if idx < 0 || idx >= columns.size then Maybe.empty
        else codec.typeName(columns(idx).typeToken)

    /** The backend's own name for the type of the column named `name`. */
    def columnTypeName(name: String): Maybe[String] =
        columnTypeName(columns.indexWhere(_.name == name))

    /** The column at `idx` rendered as text, whatever its wire type, or [[Absent]] for SQL NULL.
      *
      * The reader a generic tool needs, and the one operation `decode[String]` deliberately is not: `decode[String]` asks for a column that
      * IS text and aborts [[SqlDecodeColumnTypeMismatchException]] on one that is not, because a schema declaring `String` for a `date`
      * column is wrong about the column. This renders the value the column actually holds, decoding it at its own type first, so an `int4`
      * answers `"42"` and a `date` answers `"2026-08-04"` under either wire format.
      *
      * The rendering is the SERVER's, and the same one under either wire format. That is the whole contract: a text-protocol row already
      * carries the server's rendering and it is handed back, and a binary-protocol row is decoded and re-rendered to match it, so one
      * stored value reads as one string whichever protocol carried the row. The two do not agree on their own, and not only at the edges:
      * PostgreSQL writes a bool `t` where Java writes `true`, a timestamp `2026-08-25 10:00:00` where Java writes `2026-08-25T10:00`, and
      * a float8 1e10 `10000000000` where Java writes `1.0E10`.
      *
      * A backend renders the types it names; one it does not name falls back to reading the column's bytes as UTF-8, which is a rendering
      * of last resort rather than a decode. [[columnKind]] says which case a column is in.
      *
      * @throws SqlDecodeException
      *   if the column is out of bounds, or the value cannot be decoded at the type its column reports
      */
    def text(idx: Int)(using Frame): Maybe[String] < Abort[SqlDecodeException] =
        // Bounded against BOTH, unlike the value reads: a backend's `text` dispatches on the column's type before it
        // touches the value, so a row carrying fewer column descriptions than values would index past `columns` from
        // inside the codec, where the raised IndexOutOfBounds is outside the declared Abort. `columnKind` and
        // `columnTypeName` already guard the same way.
        if idx < 0 || idx >= values.size || idx >= columns.size then
            Abort.fail(SqlDecodeColumnOutOfBoundsException(idx, values.size))
        else if values(idx).isEmpty then Maybe.empty
        else codec.text(this, idx).map(Maybe(_))

    /** The column named `name` rendered as text, or [[Absent]] for SQL NULL.
      *
      * @throws SqlDecodeException
      *   if the column is not found, or the value cannot be decoded at the type its column reports
      */
    def text(name: String)(using Frame): Maybe[String] < Abort[SqlDecodeException] =
        val idx = columns.indexWhere(_.name == name)
        if idx < 0 then Abort.fail(SqlDecodeColumnNotFoundException(name, columnNames))
        else text(idx)
    end text

    /** Returns the raw bytes for the column at `idx`, or [[Absent]] for SQL NULL. */
    def column(idx: Int): Maybe[Span[Byte]] =
        if idx < 0 || idx >= values.size then Absent
        else values(idx)

    /** Returns the raw bytes for the column with the given name, or [[Absent]] if not found or NULL; [[hasColumn]] separates the
      * two facts.
      */
    def column(name: String): Maybe[Span[Byte]] =
        val idx = columns.indexWhere(_.name == name)
        if idx < 0 then Absent
        else values(idx)
    end column

    /** Whether the row carries a column named `name`, separating "no such column" from a column that is present but SQL NULL, which
      * [[column(name*]] both answer with [[Absent]].
      */
    def hasColumn(name: String): Boolean =
        columns.exists(_.name == name)

    /** Reads a typed value from the row's first column using the given [[SqlSchema]] evidence.
      *
      * The common case: a single-column projection, or a record type occupying every column of the row.
      *
      * A manual `decode` matches columns to fields verbatim (after any `@column` rename) when every field name is present among the
      * columns, and positionally otherwise; it does NOT apply a run-scope [[SqlNaming]] casing, because a bare row carries no query-site
      * scope. To decode a cased result set, run the query through `.run` / `.runStatic` / `.runDynamic`, whose renderer-emitted column
      * order makes the decode positional by construction.
      *
      * @throws SqlDecodeException
      *   if the row is empty or decoding fails
      */
    def decode[A](using Frame, SqlSchema[A]): A < Abort[SqlDecodeException] =
        decode[A](0)

    /** Reads a typed value from column index `idx` using the given [[SqlSchema]].
      *
      * The row's own [[SqlRow.Codec]] performs the decode, so the wire form of each column is the backend's business. For a multi-column type
      * (a derived case class, a tuple) `idx` is the first column and the schema's column count determines how many are consumed.
      *
      * For raw bytes use `column`.
      *
      * @throws SqlDecodeException
      *   if the column is out of bounds or decoding fails
      */
    def decode[A](idx: Int)(using frame: Frame, evidence: SqlSchema[A]): A < Abort[SqlDecodeException] =
        if idx < 0 || idx >= values.size then
            Abort.fail(SqlDecodeColumnOutOfBoundsException(idx, values.size))
        else codec.read(evidence, this, idx, Maybe.empty, SqlRow.FieldMatch.Verbatim)

    /** Reads a typed value from named column `name` using the given [[SqlSchema]].
      *
      * Equivalent to finding the column index by name and calling [[decode[A](idx: Int)]].
      *
      * @throws SqlDecodeException
      *   if the column is not found or decoding fails
      */
    def decode[A](name: String)(using frame: Frame, evidence: SqlSchema[A]): A < Abort[SqlDecodeException] =
        val idx = columns.indexWhere(_.name == name)
        if idx < 0 then Abort.fail(SqlDecodeColumnNotFoundException(name, columnNames))
        else decode[A](idx)
    end decode

    /** The execution path's full-row decode: decodes `schema` from column 0 with the run-scope [[SqlNaming]] casing threaded in, so a by-name
      * column match works under a casing (not only in field order). The public [[decode]] API carries no run scope and matches verbatim. Rows
      * come from a backend, never from user code, so this is `private[kyo]`.
      */
    private[kyo] def decodeRow[A](schema: SqlSchema[A], naming: Maybe[SqlNaming], fieldMatch: SqlRow.FieldMatch)(using
        Frame
    ): A < Abort[SqlDecodeException] =
        if values.isEmpty then Abort.fail(SqlDecodeColumnOutOfBoundsException(0, values.size))
        else codec.read(schema, this, 0, naming, fieldMatch)

    /** Returns a view of this row restricted to columns `[from, until)`.
      *
      * Used by tuple [[SqlSchema]] instances to feed each component reader the correct positional slice. The codec is carried over unchanged.
      * If `from >= until` or the range is out of bounds, the slice is empty.
      */
    def slice(from: Int, until: Int): SqlRow =
        new SqlRow(values.slice(from, until), columns.slice(from, until), codec)

    override def equals(that: Any): Boolean = that match
        case other: SqlRow =>
            codec == other.codec &&
            columns == other.columns &&
            values.size == other.values.size &&
            values.indices.forall { i =>
                (values(i), other.values(i)) match
                    case (Maybe.Absent, Maybe.Absent)         => true
                    case (Maybe.Present(a), Maybe.Present(b)) => a.is(b)
                    case _                                    => false
            }
        case _ => false

    override def hashCode: Int =
        var h = codec.hashCode
        h = h * 31 + columns.hashCode
        values.foreach {
            case Maybe.Absent     => h = h * 31
            case Maybe.Present(s) => h = h * 31 + java.util.Arrays.hashCode(s.toArray)
        }
        h
    end hashCode

    override def toString: String =
        val rendered = columns.zip(values).map { case (c, v) =>
            v match
                case Absent     => s"${c.name}=NULL"
                case Present(b) => s"${c.name}=${new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8)}"
        }
        s"SqlRow(${rendered.mkString(", ")})"
    end toString

end SqlRow

object SqlRow:

    /** How a row decode pairs a schema's fields with the row's columns, a property of the statement that produced the row.
      *
      *   - [[FieldMatch.Positional]]: the columns were emitted by the DSL renderer, in field order under the construction-site naming, so
      *     the n-th column feeds the n-th field and names are not consulted at decode time. This is what makes a DSL read immune to a
      *     [[SqlNaming]] given differing between the query's construction site and its run site.
      *   - [[FieldMatch.ByName]]: the SQL is user-written (a `sql"..."` fragment), so the columns arrive in the caller's order and every
      *     field of a multi-column type must resolve to a column by name, through the run-scope casing; a field that resolves to no
      *     column fails the decode with [[SqlDecodeColumnNotFoundException]] rather than silently falling back to position, which would
      *     transpose same-typed columns. Alias a computed column (`AS field_name`) to make it addressable.
      *   - [[FieldMatch.Verbatim]]: the manual [[SqlRow.decode]] contract, unchanged for rows a caller holds outside any run scope:
      *     by-name when every field name matches a column verbatim, positional otherwise.
      */
    enum FieldMatch derives CanEqual:
        case Positional, ByName, Verbatim

    given CanEqual[SqlRow, SqlRow] = CanEqual.derived

    /** Neutral column metadata.
      *
      * `typeToken` is the backend's own tag for the column's type (a PostgreSQL OID, a MySQL type byte). Core never interprets it; it exists so
      * a backend codec can dispatch on the type the server reported. A caller reading a result set it did not type wants
      * [[SqlRow.columnKind]] or [[SqlRow.columnTypeName]] rather than the token itself, both of which are this token put through the row's
      * own backend.
      *
      * @param name
      *   the column name the server reported
      * @param typeToken
      *   the backend's type tag for the column
      */
    final case class Column(val name: String, val typeToken: Int) derives CanEqual

    /** What kind of value a result column carries, in a vocabulary neither backend owns.
      *
      * The type information a caller running user-written SQL can act on: which reader to reach for, how to align a value in a table,
      * whether a filter makes sense. It is deliberately coarser than either engine's type list, since the distinctions it drops (`int2`
      * against `int8`, `varchar` against `text`) are ones the codecs already handle and a generic caller does not choose between.
      * [[SqlRow.columnTypeName]] is the engine's own spelling for the same column, for showing a human.
      *
      *   - [[Integer]], [[Decimal]], [[Float]]: exact integral, exact scaled decimal, and approximate binary floating point.
      *   - [[Bool]], [[Text]], [[Json]], [[Uuid]], [[Bytes]]: the non-numeric scalars.
      *   - [[Date]], [[Time]], [[TimeWithOffset]], [[DateTime]], [[Timestamp]], [[Interval]]: the temporal family, split the way the SQL
      *     types are.
      *   - [[Array]]: a one-dimensional array column.
      *   - [[Unknown]]: a type the backend has no neutral kind for, and a row assembled without server metadata.
      */
    enum ColumnKind derives CanEqual:
        case Integer, Decimal, Float, Bool, Text, Json, Uuid, Bytes, Date, Time, TimeWithOffset, DateTime, Timestamp, Interval, Array,
            Unknown

    /** A backend's decoder for the rows it produced.
      *
      * One instance per result set carries whatever decode context the backend needs (the wire format the server used, for one), so core can
      * hand a row back to its own backend without naming a single backend type.
      */
    abstract class Codec:
        /** Reads an `A` from `row`, starting at column `offset` and consuming the schema's column count. `naming` is the run-scope
          * [[SqlNaming]] casing (Absent for the public [[SqlRow.decode]] API), which the decoder's field matcher uses to resolve cased
          * server-column names back to schema fields.
          */
        def read[A](schema: SqlSchema[A], row: SqlRow, offset: Int, naming: Maybe[SqlNaming], fieldMatch: SqlRow.FieldMatch)(using
            Frame
        ): A < Abort[SqlDecodeException]

        /** The neutral kind of the type `typeToken` names, backing [[SqlRow.columnKind]].
          *
          * Defaults to [[SqlRow.ColumnKind.Unknown]], which is the honest answer for a codec that carries no server type metadata; a
          * backend that receives it maps its own type tags here.
          */
        def columnKind(typeToken: Int): SqlRow.ColumnKind = SqlRow.ColumnKind.Unknown

        /** The backend's own name for the type `typeToken` names, backing [[SqlRow.columnTypeName]]. Absent for a token the backend does
          * not recognise.
          */
        def typeName(typeToken: Int): Maybe[String] = Maybe.empty

        /** Renders the non-NULL column at `idx` as text, backing [[SqlRow.text]]. NULL and bounds are settled by the caller.
          *
          * Defaults to reading the column's bytes as UTF-8, which is what a value already in its text rendering needs and all a codec with
          * no type metadata can do. A backend whose result sets carry binary values decodes the value at its column's own type first.
          */
        def text(row: SqlRow, idx: Int)(using Frame): String < Abort[SqlDecodeException] =
            row.column(idx) match
                case Maybe.Present(bytes) => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                case Maybe.Absent         => Abort.fail(SqlDecodeColumnAbsentException(idx))
    end Codec

    object Codec:

        /** Two rows decoded by equal codecs came off the same kind of result set, which is what row equality needs to know. */
        private[kyo] given CanEqual[Codec, Codec] = CanEqual.derived

        /** Runs a reader-driven decode inside the typed abort the decode path promises, attributing a wrapped failure to `columnIndex`.
          *
          * Every backend decode funnels through this: a reader signals failure by throwing, so the throw is caught here and mapped to
          * `Abort[SqlDecodeException]`, keeping a decode leaf typed and wrapping anything else as a column-decode failure.
          *
          * A throwable `NonFatal` excludes is not a decode failure a caller can act on per column. There are five: a `VirtualMachineError`, a
          * `ThreadDeath`, an interrupt, a `LinkageError`, a `ControlThrowable`. Reporting one as a `SqlDecodeException` would let a caller's
          * `Result.Failure` branch absorb it and carry on, so it goes to `Abort.panic`, which does not do what its name suggests:
          * `Result.Panic.apply` rethrows such a throwable rather than carrying it as a value, so it propagates and no
          * `Abort.run[SqlDecodeException]` can recover from it by type. Not absorbing it is the point of the arm.
          *
          * `Throwable` is the only correct type parameter here, and the hand-written re-dispatch below is what narrows it. The partition this
          * method needs is `NonFatal`, and `NonFatal` is a PREDICATE while `Result.catching[E]` can only dispatch on `ConcreteTag[E]`, which
          * is a type. Every narrower candidate is wrong,
          * each in its own direction: `catching[Nothing]` matches nothing, so a genuine decode failure panics and the
          * `Abort[SqlDecodeException]` channel this method exists to provide is gone; `catching[SqlDecodeException]` panics the
          * `NumberFormatException` a decoder throws on bad digits, which is exactly a per-column decode failure a caller should handle; and
          * `catching[Exception]` looks right and silently restores the failure mode the fatal arm prevents, since `InterruptedException` is an
          * `Exception` and would come back typed.
          *
          * So `Result.catching[Throwable]` admits every throwable into `Result.Failure`, the classification is the three arms below, and the
          * `Result.Panic` arm is unreachable and present only because the match is over `Result`.
          */
        def catchingColumn[A](columnIndex: Maybe[Int])(read: => A)(using Frame): A < Abort[SqlDecodeException] =
            (Result.catching[Throwable](read): Result[Throwable, A]) match
                case Result.Success(a) => a
                case Result.Failure(e) =>
                    e match
                        case decode: SqlDecodeException => Abort.fail(decode)
                        case other if NonFatal(other)   => Abort.fail(SqlDecodeColumnDecodeException(columnIndex, other))
                        case fatal                      => Abort.panic(fatal)
                case Result.Panic(t) => Abort.panic(t)
        end catchingColumn

        /** Whole-row form of [[catchingColumn]], for a decode whose failure belongs to no single column.
          *
          * The catch is around the whole row, so there is no column to name: whichever read threw is not recoverable from here, and
          * reporting one would be picking a number.
          */
        def catching[A](read: => A)(using Frame): A < Abort[SqlDecodeException] =
            catchingColumn(Absent)(read)
    end Codec

end SqlRow
