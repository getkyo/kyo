package kyo

import java.nio.charset.StandardCharsets
import kyo.db.Idiom
import kyo.internal.postgres.HstoreReader
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.summonInline
import scala.deriving.Mirror

/** Schemas for the column types only PostgreSQL has.
  *
  * Each schema here writes through [[kyo.SqlCodec.Writer.extension]], the channel that carries a value one dialect owns together with the
  * dialect that owns it. A MySQL backend asked to write one of these aborts with [[SqlUnsupportedTypeOnBackendException]] instead of emitting
  * bytes the server cannot read, so a program that reaches for `hstore` is portable at compile time and typed-failing at run time rather than
  * silently wrong.
  *
  * [[custom]] is the escape for a type this object does not cover, including every extension type (`geometry`, `vector`, a user
  * `CREATE TYPE`). The type name it takes must be declared in [[PostgresConfig.typeNames]] so the session can resolve its OID from `pg_type`.
  */
object PostgresTypes:

    /** The dialect that owns every type in this object. */
    private val dialect: Idiom.Id = Idiom.Id("postgres")

    /** A PostgreSQL `hstore` value: a flat string-to-string map whose values may be SQL NULL.
      *
      * @param entries
      *   the map's entries; [[Maybe.Absent]] is a NULL value, distinct from an absent key
      */
    final case class HStore(entries: Map[String, Maybe[String]]) derives CanEqual

    /** A PostgreSQL range value: an interval over `A` whose ends may each be inclusive, exclusive, or unbounded.
      *
      * @param lower
      *   the lower end
      * @param upper
      *   the upper end
      */
    final case class Range[A](lower: Range.Bound[A], upper: Range.Bound[A]) derives CanEqual

    object Range:

        /** One end of a [[Range]]. */
        enum Bound[+A] derives CanEqual:
            /** The end is `v` and `v` belongs to the range. */
            case Inclusive(v: A)

            /** The end is `v` and `v` does not belong to the range. */
            case Exclusive(v: A)

            /** The range has no end on this side. */
            case Unbounded
        end Bound
    end Range

    /** Builds a schema for a PostgreSQL type this object does not cover.
      *
      * The write and read functions are the same shape [[SqlSchema.of]] takes, so they compose from the SQL type vocabulary: a `geometry`
      * value emits `w.extension(...)` naming the `pg_type` in its payload, a domain over `text` emits `w.string(...)`. The result is
      * single-column SQL-support evidence ([[SqlSchema.Column]]), so it is admitted at a bind or row position; install it as
      * `given SqlSchema.Column[A] = PostgresTypes.custom(...)`. Casting to a custom type (`expr.cast[A]`) reads a `given SqlType[A]` rather
      * than the evidence, so declare one with `given SqlType[A] = SqlType.of(SqlType.Type.Extension("geometry"))` to make the type castable.
      *
      * @param write
      *   emits the value's column
      * @param read
      *   consumes the value's column
      */
    def custom[A](write: (A, SqlCodec.Writer) => Unit)(read: SqlCodec.Reader => A): SqlSchema.Column[A] =
        SqlSchema.of[A](write, read)

    /** Schema for [[HStore]], the PostgreSQL `hstore` column type.
      *
      * Wire form (binary): `int32 count`, then per entry `int32 keyLength + key bytes` and `int32 valueLength + value bytes`, where a value
      * length of `-1` is SQL NULL. Keys and values are UTF-8. The write always emits the binary form; the read takes whichever form the
      * column arrived in, since every column of a simple query is text.
      */
    given hstoreColumn: SqlSchema.Column[HStore] = SqlSchema.of[HStore](
        write = (v, w) => w.extension(SqlCodec.Writer.Payload(dialect, "hstore", SqlCodec.Format.Binary, encodeHStore(v))),
        read = r =>
            val ext = r.nextExtension(dialect, "hstore")
            decodeHStore(ext.bytes, ext.format, r.frame)
    )

    /** Names the PostgreSQL range type whose element is `A`.
      *
      * PostgreSQL has six builtin range types and nothing about `SqlSchema.Column[A]` selects among them, so the choice is carried by an explicit
      * given rather than derived. That also makes the set closed: `Range[String]` does not compile, because PostgreSQL has no string range,
      * and a range over a type this companion does not know is a compile error rather than a payload the server rejects at execution time.
      *
      * For a user-defined range type (`CREATE TYPE ... AS RANGE`), reach for [[custom]] with the concrete type name instead of adding a
      * given here; those types have no fixed OID and must be resolved through [[PostgresConfig.typeNames]].
      */
    final case class RangeKind[A](typeName: String)

    object RangeKind:
        given RangeKind[Int]                      = RangeKind("int4range")
        given RangeKind[Long]                     = RangeKind("int8range")
        given RangeKind[BigDecimal]               = RangeKind("numrange")
        given RangeKind[java.time.LocalDate]      = RangeKind("daterange")
        given RangeKind[java.time.LocalDateTime]  = RangeKind("tsrange")
        given RangeKind[java.time.Instant]        = RangeKind("tstzrange")
        given RangeKind[java.time.OffsetDateTime] = RangeKind("tstzrange")
    end RangeKind

    /** Schema for [[Range]], the PostgreSQL range column types.
      *
      * Wire form (binary): `flags(1)`, then for each present end `int32 length + element bytes`. The flag bits are the server's own:
      * `0x01` empty, `0x02` lower inclusive, `0x04` upper inclusive, `0x08` lower unbounded, `0x10` upper unbounded.
      *
      * The element bytes are what the active writer encodes for a single column, obtained through
      * [[kyo.SqlCodec.Writer.encodeElement]] so this companion composes the payload without naming a wire type. The payload's layout fixes
      * its elements' format, so the demand is [[kyo.SqlCodec.Format.Binary]] and a backend whose encoder for the element type produces text
      * refuses with [[SqlUnsupportedElementFormatException]] instead of inlining bytes this schema would read back as the other form. The
      * element schema must occupy exactly one column ([[SqlUnsupportedMultiColumnElementException]]) and a range end cannot be absent
      * ([[SqlUnsupportedAbsentElementException]], PostgreSQL spelling an absent end as unbounded). Reading back the empty range fails with
      * [[SqlDecodeEmptyRangeException]], because two bounds cannot express it.
      *
      * Only the binary form is read. A range column of a simple-query result arrives as PostgreSQL's own `[1,10)` rendering, which is a
      * grammar rather than a header, so the read refuses it by name rather than reading the `[` as a flags byte.
      *
      * The range type's name comes from [[RangeKind]], so only the six builtin ranges are available and any other element type fails to
      * compile.
      */
    given rangeColumn[A](using element: SqlSchema.Column[A], kind: RangeKind[A], elementTag: ConcreteTag[A]): SqlSchema.Column[Range[A]] =
        SqlSchema.of[Range[A]](
            write = (v, w) =>
                w.extension(
                    SqlCodec.Writer.Payload(dialect, kind.typeName, SqlCodec.Format.Binary, encodeRange(v, element, elementTag.showType, w))
                ),
            read = r =>
                val ext = r.nextExtension(dialect, kind.typeName)
                decodeRange(ext.bytes, ext.format, element, elementTag.showType, r)
        )

    /** Builds a schema for a PostgreSQL `enum` type, mapping each variant of `E` to the label the server stores.
      *
      * The variant name IS the label, so `enum Mood { case Happy, Sad }` matches `CREATE TYPE mood AS ENUM ('Happy', 'Sad')`. A label the
      * server sends that names no variant aborts with [[SqlDecodeSumTypeUnknownLabelException]].
      *
      * `typeName` must be declared in [[PostgresConfig.typeNames]] so the session can resolve the enum's OID from `pg_type`.
      *
      * `inline` is mandatory: the body reads the variant labels from the `Mirror.SumOf` at the call site and acquires `ConcreteTag[E]` there
      * too, because [[ConcreteTag]] derivation cannot run against an abstract type parameter.
      *
      * @param typeName
      *   the enum type's own name in `pg_type`, e.g. `"mood"`
      */
    inline def pgEnum[E <: reflect.Enum](inline typeName: String)(using m: Mirror.SumOf[E]): SqlSchema.Column[E] =
        val labels   = Chunk.from(collectLabels[m.MirroredElemLabels])
        val variants = Chunk.from(collectVariants[E, m.MirroredElemTypes])
        enumSchema[E](typeName, labels, variants, m)
    end pgEnum

    /** Non-inline body of [[pgEnum]]: the labels and the variant values are already materialized. */
    private def enumSchema[E](typeName: String, labels: Chunk[String], variants: Chunk[E], m: Mirror.SumOf[E]): SqlSchema.Column[E] =
        SqlSchema.of[E](
            // A PostgreSQL enum's text and binary representations are both the label's UTF-8 bytes, so the write states binary and the read
            // ignores the format it is given. Carrying the format does not oblige every closure to branch on it.
            write = (v, w) =>
                val bytes = Span.from(labels(m.ordinal(v)).getBytes(StandardCharsets.UTF_8))
                w.extension(SqlCodec.Writer.Payload(dialect, typeName, SqlCodec.Format.Binary, bytes))
            ,
            read = r =>
                val label = new String(r.nextExtension(dialect, typeName).bytes.toArray, StandardCharsets.UTF_8)
                val ord   = labels.indexOf(label)
                if ord < 0 then
                    throw SqlDecodeSumTypeUnknownLabelException(label, labels)(using r.frame)
                end if
                variants(ord)
        )
    end enumSchema

    /** Collects the singleton value of each variant type in `Types`, ordered by the mirror's ordinals.
      *
      * The cast is unavoidable and matches the one `Sql.enumText` makes for the same reason: `Mirror.SumOf[E]`
      * guarantees every element type is a subtype of `E`, but tuple-typed inline traversal loses that relationship, and the intersection
      * `ValueOf[h & E]` is not a singleton type the compiler will synthesize a `ValueOf` for.
      */
    private inline def collectVariants[E, Types <: Tuple]: List[E] =
        inline erasedValue[Types] match
            case _: EmptyTuple => Nil
            case _: (h *: t)   => summonInline[ValueOf[h]].value.asInstanceOf[E] :: collectVariants[E, t]

    /** Collects the compile-time string literal at each position of `Labels` into an ordered list. */
    private inline def collectLabels[Labels <: Tuple]: List[String] =
        inline erasedValue[Labels] match
            case _: EmptyTuple => Nil
            case _: (h *: t)   => constValue[h & String] :: collectLabels[t]

    // --- hstore wire form ---

    private def encodeHStore(value: HStore): Span[Byte] =
        val out = Array.newBuilder[Byte]
        appendInt32(out, value.entries.size)
        value.entries.foreach { case (key, maybeValue) =>
            val keyBytes = key.getBytes(StandardCharsets.UTF_8)
            appendInt32(out, keyBytes.length)
            out ++= keyBytes
            maybeValue match
                case Maybe.Present(v) =>
                    val valueBytes = v.getBytes(StandardCharsets.UTF_8)
                    appendInt32(out, valueBytes.length)
                    out ++= valueBytes
                case Maybe.Absent =>
                    appendInt32(out, -1)
            end match
        }
        Span.from(out.result())
    end encodeHStore

    /** Parses an `hstore` payload through [[kyo.internal.postgres.HstoreReader]], the backend's own hstore parser.
      *
      * The format comes from the column rather than being assumed: `HstoreReader` reads both the binary header form and `hstore_out`'s
      * `"a"=>"1"` rendering, and every column of a `simpleQuery` result arrives in the second one.
      */
    private def decodeHStore(bytes: Span[Byte], format: SqlCodec.Format, frame: Frame): HStore =
        val reader  = new HstoreReader(bytes, format, frame)
        var left    = reader.openMap()
        val entries = Map.newBuilder[String, Maybe[String]]
        while left > 0 do
            val key = reader.nextKey()
            entries += key -> reader.nextValue()
            left -= 1
        end while
        HStore(entries.result())
    end decodeHStore

    // --- range wire form ---

    private inline val RangeEmpty          = 0x01
    private inline val RangeLowerInclusive = 0x02
    private inline val RangeUpperInclusive = 0x04
    private inline val RangeLowerUnbounded = 0x08
    private inline val RangeUpperUnbounded = 0x10

    /** The `pg_type` name the range goes out under, derived from what the element schema casts to. */
    private def encodeRange[A](value: Range[A], element: SqlSchema.Column[A], typeName: String, writer: SqlCodec.Writer): Span[Byte] =
        given Frame = writer.frame
        var flags   = 0
        value.lower match
            case Range.Bound.Inclusive(_) => flags |= RangeLowerInclusive
            case Range.Bound.Exclusive(_) => ()
            case Range.Bound.Unbounded    => flags |= RangeLowerUnbounded
        end match
        value.upper match
            case Range.Bound.Inclusive(_) => flags |= RangeUpperInclusive
            case Range.Bound.Exclusive(_) => ()
            case Range.Bound.Unbounded    => flags |= RangeUpperUnbounded
        end match

        val out = Array.newBuilder[Byte]
        out += flags.toByte
        boundValue(value.lower).foreach(v => appendElement(out, element, v, typeName, writer))
        boundValue(value.upper).foreach(v => appendElement(out, element, v, typeName, writer))
        Span.from(out.result())
    end encodeRange

    private def boundValue[A](bound: Range.Bound[A]): Maybe[A] =
        bound match
            case Range.Bound.Inclusive(v) => Maybe(v)
            case Range.Bound.Exclusive(v) => Maybe(v)
            case Range.Bound.Unbounded    => Maybe.Absent

    /** Appends one range end as `int32 length + element bytes`, the element bytes being what the active writer encodes for one column.
      *
      * The binary payload holds binary elements, so that is what the writer is asked for; a writer whose encoder for the element type only
      * produces text refuses with [[SqlUnsupportedElementFormatException]], which names the backend because the limit is the backend's. The
      * one-column and present-value requirements are enforced by [[kyo.SqlCodec.Writer.encodeElement]] too, which raises
      * [[SqlUnsupportedMultiColumnElementException]] or [[SqlUnsupportedAbsentElementException]]. Neither of those names a dialect: no
      * flavor's composite wire form has room for a second column or for an absent element, so the refusal is not the backend's.
      */
    private def appendElement[A](
        out: scala.collection.mutable.Builder[Byte, Array[Byte]],
        element: SqlSchema.Column[A],
        value: A,
        typeName: String,
        writer: SqlCodec.Writer
    ): Unit =
        val bytes = writer.encodeElement(element, value, typeName, SqlCodec.Format.Binary)
        appendInt32(out, bytes.size)
        var i = 0
        while i < bytes.size do
            out += bytes(i)
            i += 1
    end appendElement

    private def decodeRange[A](
        bytes: Span[Byte],
        format: SqlCodec.Format,
        element: SqlSchema.Column[A],
        typeName: String,
        reader: SqlCodec.Reader
    ): Range[A] =
        given Frame = reader.frame
        if format != SqlCodec.Format.Binary then
            // `[1,10)` opens with 0x5B, whose low bit is the binary form's EMPTY flag, so parsing a text rendering
            // as a header answers "this is the empty range" for a range that holds ten values.
            throw SqlUnsupportedDialectFeatureException(
                "reading a range value in the text wire format",
                dialect,
                Maybe.Absent,
                Maybe.Absent
            )
        end if
        if bytes.isEmpty then throw SqlDecodeInsufficientBytesException("range", 1, 0, 0)
        val flags = bytes(0).toInt & 0xff
        if (flags & RangeEmpty) != 0 then
            // The empty range holds no values, which two bounds cannot express: an unbounded pair would
            // read back as the range of every value, the exact opposite.
            //
            // The element's name is captured from the element's ConcreteTag at the range factory, where the
            // concrete type is in scope, and threaded here as `typeName` (a Schema carries no runtime tag).
            throw SqlDecodeEmptyRangeException(s"Range[$typeName]")
        else
            var offset = 1
            val lower =
                if (flags & RangeLowerUnbounded) != 0 then Range.Bound.Unbounded
                else
                    val length = readInt32(bytes, offset, "range lower length")
                    offset += 4
                    val v = readElement(bytes, offset, length, element, reader)
                    offset += length
                    if (flags & RangeLowerInclusive) != 0 then Range.Bound.Inclusive(v) else Range.Bound.Exclusive(v)
            val upper =
                if (flags & RangeUpperUnbounded) != 0 then Range.Bound.Unbounded
                else
                    val length = readInt32(bytes, offset, "range upper length")
                    offset += 4
                    val v = readElement(bytes, offset, length, element, reader)
                    offset += length
                    if (flags & RangeUpperInclusive) != 0 then Range.Bound.Inclusive(v) else Range.Bound.Exclusive(v)
            Range(lower, upper)
        end if
    end decodeRange

    /** Reads one range end's element from `length` bytes at `offset` through the active reader's own column decoder.
      *
      * The slice is binary because the payload it was cut out of is, which is the coordinate the reader cannot supply for itself: its own
      * column's format describes the whole range, not the element inside it.
      */
    private def readElement[A](bytes: Span[Byte], offset: Int, length: Int, element: SqlSchema.Column[A], reader: SqlCodec.Reader): A =
        given Frame = reader.frame
        if length < 0 then throw SqlDecodeInsufficientBytesException("range element", 0, length, offset)
        if offset + length > bytes.size then
            throw SqlDecodeInsufficientBytesException("range element", length, bytes.size - offset, offset)
        reader.decodeElement(element, bytes.slice(offset, offset + length), SqlCodec.Format.Binary)
    end readElement

    // --- big-endian helpers ---
    //
    // The payload assembly here writes into a plain byte builder rather than a backend buffer type, so this
    // file names no backend wire class beyond the param writer it needs for element bytes.

    private def appendInt32(out: scala.collection.mutable.Builder[Byte, Array[Byte]], value: Int): Unit =
        out += ((value >>> 24) & 0xff).toByte
        out += ((value >>> 16) & 0xff).toByte
        out += ((value >>> 8) & 0xff).toByte
        out += (value & 0xff).toByte
    end appendInt32

    private def readInt32(bytes: Span[Byte], offset: Int, what: String)(using Frame): Int =
        if offset + 4 > bytes.size then
            throw SqlDecodeInsufficientBytesException(what, 4, bytes.size - offset, offset)
        end if
        ((bytes(offset) & 0xff) << 24) |
            ((bytes(offset + 1) & 0xff) << 16) |
            ((bytes(offset + 2) & 0xff) << 8) |
            (bytes(offset + 3) & 0xff)
    end readInt32

end PostgresTypes
