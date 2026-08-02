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
    private[kyo] val columns: Chunk[SqlRow.Column],
    private[kyo] val codec: SqlRow.Codec
):

    /** Number of columns in this row. */
    def size: Int = values.size

    /** The column names, in order. */
    def columnNames: Chunk[String] = columns.map(_.name)

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
        if idx < 0 then Abort.fail(SqlDecodeColumnNotFoundException(name))
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
      * a backend codec can dispatch on the type the server reported.
      *
      * @param name
      *   the column name the server reported
      * @param typeToken
      *   the backend's type tag for the column
      */
    final case class Column(val name: String, val typeToken: Int) derives CanEqual

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
