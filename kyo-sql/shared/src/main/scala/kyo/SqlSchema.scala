package kyo

import scala.annotation.implicitNotFound
import scala.annotation.publicInBinary
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.error
import scala.compiletime.summonFrom
import scala.deriving.Mirror

/** The SQL codec typeclass: how `A` is written to and read from SQL columns, and the proof that `A` is SQL-storable at all.
  *
  * Self-contained: an `SqlSchema` carries its own write and read over the SQL writer and reader vocabulary ([[SqlCodec.Writer]],
  * [[SqlCodec.Reader]]), plus the column count and column names the transport needs. It does not wrap, extend, or delegate to any other
  * serialization typeclass, so what an instance does is exactly what its column codecs do.
  *
  * Support is the presence of an instance. The base [[SqlSchema.Column]] givens enumerate the supported scalar set, `Maybe` and `Option` of
  * a column are columns, and a case class or tuple derives a row instance exactly when every field is a single column, so an unsupported
  * type is a compile error at the call site, in generic `[A: SqlSchema]` code as much as at a concrete query. There is no runtime support
  * failure path.
  *
  * [[SqlSchema.Column]] is the single-column tier: what bind positions and derived row fields require. A custom single-column encoding is
  * installed with [[SqlSchema.of]], `Sql.jsonColumn`, or `Sql.enumText`; a multi-column custom codec with [[SqlSchema.ofMulti]]. Column
  * names honor the SQL-specific `@column` rename; the query-scoped [[SqlNaming]] casing is applied downstream by the field matcher,
  * never baked into an instance.
  */
@implicitNotFound(
    "${A} is not a SQL-storable type.\n" +
        "Supported: primitives, temporals, UUID, URI/Locale/Currency, Span[Byte], JsonText,\n" +
        "Chunk[Int]/Chunk[String]/Chunk[JsonText], Maybe/Option of a supported type, and case\n" +
        "classes or tuples whose fields are all single-column types.\n" +
        "For a custom single-column encoding install a given SqlSchema.Column (SqlSchema.of,\n" +
        "Sql.jsonColumn, Sql.enumText); for a multi-column custom codec use SqlSchema.ofMulti."
)
sealed abstract class SqlSchema[A]:

    /** Number of SQL columns a value of `A` occupies. */
    private[kyo] def width: Int

    /** The SQL column names of a row type, post `@column` rename and before any query-scoped [[SqlNaming]] casing. Empty for a
      * single column, whose name is decided by the position it is bound or selected into.
      */
    private[kyo] def fieldNames: Chunk[String]

    /** Writes `value` as `width` columns to `writer`. */
    private[kyo] def write(value: A, writer: SqlCodec.Writer): Unit

    /** Reads a value of `A`, consuming `width` columns from `reader`. Decode failures are thrown ([[SqlDecodeException]]) and converted to
      * `Abort` at the transport boundary.
      */
    private[kyo] def read(reader: SqlCodec.Reader): A

end SqlSchema

object SqlSchema extends kyo.internal.LowPrioritySqlSchema:

    /** A single SQL column: the codec for exactly one column of `A`. This is the tier bind positions require ([[Sql.literal]], comparison
      * and arithmetic operators, `:=`, `in`, `between`, CASE branches, INSERT cells) and the tier a derived row requires of each field.
      * Every `Column[A]` is an `SqlSchema[A]` by subtyping.
      */
    @implicitNotFound(
        "${A} cannot occupy a single SQL column.\n" +
            "A case class or tuple is a row (many columns), not a bind value. To bind it as one\n" +
            "column, install a single-column codec: given SqlSchema.Column[${A}] = Sql.jsonColumn."
    )
    final class Column[A] private[kyo] (
        writeFn: (A, SqlCodec.Writer) => Unit,
        readFn: SqlCodec.Reader => A
    ) extends SqlSchema[A]:
        private[kyo] def width: Int                                     = 1
        private[kyo] def fieldNames: Chunk[String]                      = Chunk.empty
        private[kyo] def write(value: A, writer: SqlCodec.Writer): Unit = writeFn(value, writer)
        private[kyo] def read(reader: SqlCodec.Reader): A               = readFn(reader)
    end Column

    object Column:

        /** `derives SqlSchema.Column` for a singleton-variant sum type: the value stores as its variant label in one `TEXT` column.
          *
          * The derivation is [[Sql.enumText]]: every variant must be a case object or no-argument enum case, the variant name is the
          * stored label, and a label read back that names no variant aborts with [[SqlDecodeSumTypeUnknownLabelException]]. A sum type
          * with data-carrying variants does not derive a column; store it with [[Sql.jsonColumn]] instead.
          */
        inline def derived[E](using m: Mirror.SumOf[E]): Column[E] = Sql.enumText[E]
    end Column

    // --- Base scalar columns ---
    //
    // The closed supported set, each a native codec over the SQL vocabulary. Plain vals so the static-SQL lift resolves them by
    // reflection as stable givens.

    given int: Column[Int]         = new Column((v, w) => w.int(v), r => r.int())
    given long: Column[Long]       = new Column((v, w) => w.long(v), r => r.long())
    given string: Column[String]   = new Column((v, w) => w.string(v), r => r.string())
    given boolean: Column[Boolean] = new Column((v, w) => w.boolean(v), r => r.boolean())
    given float: Column[Float]     = new Column((v, w) => w.float(v), r => r.float())
    given double: Column[Double]   = new Column((v, w) => w.double(v), r => r.double())
    given short: Column[Short]     = new Column((v, w) => w.short(v), r => r.short())
    given byte: Column[Byte]       = new Column((v, w) => w.byte(v), r => r.byte())
    given char: Column[Char]       = new Column((v, w) => w.char(v), r => r.char())

    given bigDecimal: Column[BigDecimal] = new Column((v, w) => w.bigDecimal(v), r => r.bigDecimal())
    given bigInt: Column[BigInt]         = new Column((v, w) => w.bigInt(v), r => r.bigInt())
    given spanByte: Column[Span[Byte]]   = new Column((v, w) => w.bytes(v), r => r.bytes())

    given javaInstant: Column[java.time.Instant]         = new Column((v, w) => w.instant(v), r => r.instant())
    given javaDuration: Column[java.time.Duration]       = new Column((v, w) => w.duration(v), r => r.duration())
    given localDate: Column[java.time.LocalDate]         = new Column((v, w) => w.date(v), r => r.nextDate())
    given localTime: Column[java.time.LocalTime]         = new Column((v, w) => w.time(v), r => r.nextTime())
    given localDateTime: Column[java.time.LocalDateTime] = new Column((v, w) => w.dateTime(v), r => r.nextDateTime())
    given offsetTime: Column[java.time.OffsetTime]       = new Column((v, w) => w.timeWithOffset(v), r => r.nextTimeWithOffset())
    given period: Column[java.time.Period]               = new Column((v, w) => w.calendarInterval(v), r => r.nextCalendarInterval())

    /** `OffsetDateTime` and `ZonedDateTime` normalise to the instant wire form: the offset or zone is application-level metadata that
      * `timestamptz` and `DATETIME` do not persist, so a round-trip reconstructs the same instant at UTC.
      */
    given offsetDateTime: Column[java.time.OffsetDateTime] =
        new Column(
            (v, w) => w.instant(v.toInstant),
            r => java.time.OffsetDateTime.ofInstant(r.instant(), java.time.ZoneOffset.UTC)
        )
    given zonedDateTime: Column[java.time.ZonedDateTime] =
        new Column(
            (v, w) => w.instant(v.toInstant),
            r => java.time.ZonedDateTime.ofInstant(r.instant(), java.time.ZoneOffset.UTC)
        )

    given kyoInstant: Column[kyo.Instant] =
        new Column((v, w) => w.instant(v.toJava), r => kyo.Instant.fromJava(r.instant()))

    /** `kyo.Duration` stores total nanoseconds as a 64-bit integer column. */
    given kyoDuration: Column[kyo.Duration] =
        new Column((v, w) => w.long(v.toNanos), r => kyo.Duration.fromNanos(r.long()))

    /** `FiniteDuration` round-trips by total nanoseconds through the duration wire; the decoded value is normalized to the coarsest exact
      * unit.
      */
    given finiteDuration: Column[scala.concurrent.duration.FiniteDuration] =
        new Column(
            (v, w) => w.duration(java.time.Duration.ofNanos(v.toNanos)),
            r => scala.concurrent.duration.Duration.fromNanos(r.duration().toNanos)
        )

    given javaUuid: Column[java.util.UUID] = new Column((v, w) => w.uuid(v), r => r.nextUuid())

    /** `kyo.UUID` stores the canonical 36-character text form. */
    given kyoUuid: Column[kyo.UUID] =
        new Column(
            (v, w) => w.string(v.show),
            r =>
                val text = r.string()
                kyo.UUID.parse(text)(using r.frame).foldOrThrow(
                    identity,
                    _ => throw SqlDecodeInvalidTextException("UUID", text)(using r.frame)
                )
        )

    given uri: Column[java.net.URI] =
        new Column((v, w) => w.string(v.toString), r => java.net.URI.create(r.string()))
    // java.util.Locale as the DIRECT result type of a pending computation crashes on Scala.js (union
    // erasure lub cast in the runtime); as a case-class field it is fine.
    given locale: Column[java.util.Locale] =
        new Column((v, w) => w.string(v.toLanguageTag), r => java.util.Locale.forLanguageTag(r.string()))
    given currency: Column[java.util.Currency] =
        new Column((v, w) => w.string(v.getCurrencyCode), r => java.util.Currency.getInstance(r.string()))

    // --- Sanctioned single-column collections ---
    //
    // The whole-chunk array columns. No column exists for Chunk[A] generally, for List/Vector/Set/Seq, or for Map: those have no SQL
    // evidence and are compile errors at SQL positions. Dynamic JSON documents and JSON arrays go through [[JsonText]].

    given chunkInt: Column[Chunk[Int]]       = new Column((v, w) => w.arrayOfInt(v), r => r.nextArrayOfInt())
    given chunkString: Column[Chunk[String]] = new Column((v, w) => w.arrayOfString(v), r => r.nextArrayOfString())

    // --- Nullability (a nullable single column) ---
    //
    // The reader contract makes this exact: `isNil()` returns true AND consumes the null column; on a non-null column it leaves the
    // cursor for the inner read. Both backends implement it that way.

    /** A nullable single column: `Maybe` of a single-column type is itself a single column. */
    given maybe[A](using inner: Column[A]): Column[Maybe[A]] =
        new Column(
            (v, w) =>
                v match
                    case Maybe.Present(x) => inner.write(x, w)
                    case _                => w.nil(),
            r => if r.isNil() then Maybe.empty else Maybe(inner.read(r))
        )

    /** A nullable single column: `Option` of a single-column type is itself a single column. */
    given option[A](using inner: Column[A]): Column[Option[A]] =
        new Column(
            (v, w) =>
                v match
                    case Some(x) => inner.write(x, w)
                    case None    => w.nil(),
            r => if r.isNil() then None else Some(inner.read(r))
        )

    // --- Explicit installs: the escape hatches ---
    //
    // All are defs (never givens), so none participates in implicit search; the user's `given` declaration is the conscious act that
    // admits the type.

    /** Custom single-column codec (geometry, JSONB, hstore, pgvector). Declaring the column's cast target is a separate concern:
      * `expr.cast[A]` reads a `given SqlType[A]`, not this codec, so declare a [[SqlType]] to make `A` castable (see [[SqlType.of]]).
      *
      * @param write
      *   writes the value to a [[SqlCodec.Writer]] (called once per bind execution)
      * @param read
      *   reads the value from a [[SqlCodec.Reader]] (called once per result row)
      */
    def of[A](write: (A, SqlCodec.Writer) => Unit, read: SqlCodec.Reader => A): Column[A] =
        new Column(write, read)

    /** Multi-column custom codec, for an encoding that spans more than one SQL column.
      *
      * `fieldNames` fixes the column count: `write` must emit exactly `fieldNames.size` columns and `read` must consume exactly that many.
      * Returns row evidence ([[SqlSchema]]), not [[Column]]: legal as a whole result row, statically illegal at a bind, where the
      * [[Column]] tier does not resolve.
      *
      * @param fieldNames
      *   logical column names in declaration order
      * @param write
      *   writes the value's columns to a [[SqlCodec.Writer]] in declaration order
      * @param read
      *   reads the value's columns from a [[SqlCodec.Reader]] in declaration order
      */
    def ofMulti[A](fieldNames: Seq[String])(write: (A, SqlCodec.Writer) => Unit)(read: SqlCodec.Reader => A): SqlSchema[A] =
        val names   = Chunk.from(fieldNames)
        val writeFn = write
        val readFn  = read
        new SqlSchema[A]:
            private[kyo] def width: Int                                     = names.size
            private[kyo] def fieldNames: Chunk[String]                      = names
            private[kyo] def write(value: A, writer: SqlCodec.Writer): Unit = writeFn(value, writer)
            private[kyo] def read(reader: SqlCodec.Reader): A               = readFn(reader)
        end new
    end ofMulti

    /** Builds the derived row instance. Non-inline so its body compiles in package kyo; `@publicInBinary` so the inline `derived` given can
      * call it from a user call site.
      */
    @publicInBinary
    private[kyo] def row[P](names: Seq[String], columns: List[Column[?]])(using m: Mirror.ProductOf[P]): SqlSchema[P] =
        new Row[P](Chunk.from(names), Chunk.from(columns), m)

    /** The derived row codec: writes fields in declaration order through each field's own [[Column]]; reads columns in incoming order,
      * routing each to its field slot through the reader's field matcher (so a by-name decode survives a column order the row did not
      * declare), then constructs through the mirror.
      */
    final private class Row[P](names: Chunk[String], columns: Chunk[Column[?]], mirror: Mirror.ProductOf[P]) extends SqlSchema[P]:
        private[kyo] def width: Int                = columns.size
        private[kyo] def fieldNames: Chunk[String] = names

        private[kyo] def write(value: P, writer: SqlCodec.Writer): Unit =
            // Erasure boundary: Mirror.ProductOf[P] guarantees P is a Product, and columns(i) was built for
            // field i's type; the heterogeneous Chunk erases it to Column[?].
            val product = value.asInstanceOf[Product]
            var i       = 0
            while i < columns.size do
                columns(i).asInstanceOf[Column[Any]].write(product.productElement(i), writer)
                i += 1
        end write

        private[kyo] def read(reader: SqlCodec.Reader): P =
            val n     = columns.size
            val slots = new Array[Any](n)
            var i     = 0
            // Erasure boundary: columns(jj) was built for field jj's type; the heterogeneous Chunk erases it to Column[?].
            while i < n do
                val j  = reader.fieldIndex(i, names)
                val jj = if j < 0 || j >= n then i else j
                slots(jj) = columns(jj).asInstanceOf[Column[Any]].read(reader)
                i += 1
            end while
            mirror.fromProduct(Tuple.fromArray(slots))
        end read
    end Row

    /** The nullable embedded row: absent writes one NULL per column and a null first column reads as absent, consuming the row's remaining
      * columns.
      */
    final private class MaybeRow[P, M](inner: SqlSchema[P], wrap: Maybe[P] => M, unwrap: M => Maybe[P]) extends SqlSchema[M]:
        private[kyo] def width: Int                = inner.width
        private[kyo] def fieldNames: Chunk[String] = inner.fieldNames

        private[kyo] def write(value: M, writer: SqlCodec.Writer): Unit =
            unwrap(value) match
                case Maybe.Present(p) => inner.write(p, writer)
                case _ =>
                    var i = 0
                    while i < inner.width do
                        writer.nil()
                        i += 1

        private[kyo] def read(reader: SqlCodec.Reader): M =
            if reader.isNil() then
                // isNil consumed the first (null) column; consume the row's remaining columns.
                var i = 1
                while i < inner.width do
                    reader.skip()
                    i += 1
                wrap(Maybe.empty)
            else
                wrap(Maybe(inner.read(reader)))
    end MaybeRow

    @publicInBinary
    private[kyo] def maybeRowOf[P](inner: SqlSchema[P]): SqlSchema[Maybe[P]] =
        new MaybeRow[P, Maybe[P]](inner, identity, identity)

    @publicInBinary
    private[kyo] def optionRowOf[P](inner: SqlSchema[P]): SqlSchema[Option[P]] =
        new MaybeRow[P, Option[P]](inner, _.toOption, Maybe.fromOption)

end SqlSchema
