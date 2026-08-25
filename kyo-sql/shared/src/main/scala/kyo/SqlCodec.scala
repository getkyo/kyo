package kyo

import kyo.db.Idiom

/** The SQL wire vocabulary: the writer a value's columns are emitted through, the reader a row's columns are consumed from, and the wire
  * [[SqlCodec.Format]] the two speak in.
  *
  * [[SqlCodec.Writer]] and [[SqlCodec.Reader]] are the complete, self-contained serialization contract of the SQL layer: one method per
  * scalar primitive and one per SQL type the primitives cannot express. Each method names what the value IS, never how a backend spells
  * it: `dateTime` says "wall-clock date and time", and the Postgres and MySQL implementations pick `TIMESTAMP` binary and `DATETIME`
  * binary respectively, so an [[SqlSchema]] written against this vocabulary runs unchanged on either backend. The backends supply the
  * concrete implementations.
  *
  * A user meets these classes as the arguments of the write and read functions handed to [[SqlSchema.of]] and [[SqlSchema.ofMulti]] or a
  * backend's own custom-type factory.
  */
object SqlCodec:

    /** Which of the two wire representations a parameter or a result column uses.
      *
      * Text is the value's locale-independent ASCII rendering; binary is type-specific and more compact. Every value crossing the wire is
      * one or the other, so a decode that does not know which one it is holding is guessing: the ASCII digits of `1234` read as a
      * little-endian 64-bit integer give 875770417, and the ASCII `0` of a false boolean is byte 0x30, which is nonzero as a byte and zero
      * as a number.
      *
      * A user meets this enum on the transport: [[SqlCodec.Writer.Payload.format]] states the form the payload's author encoded, and
      * [[SqlCodec.Reader.Extension.format]] reports the form the server sent, which a read closure branches on.
      *
      * Both backends carry this distinction, which is why it lives in core rather than in either engine. The two protocols differ only in
      * where the choice is made: PostgreSQL negotiates it per parameter and per column, while MySQL fixes it per protocol, text for simple
      * queries and binary for prepared statements.
      *
      * The `code` values are PostgreSQL's, from §55.7 "Bind" and "RowDescription". They stay internal: a backend speaking another protocol
      * has no use for them, and one speaking PostgreSQL's spells the two-value mapping itself.
      */
    enum Format derives CanEqual:
        case Text, Binary

        /** The Int16 format code used on the wire: 0 for Text, 1 for Binary. */
        private[kyo] inline def code: Short = this match
            case Text   => 0.toShort
            case Binary => 1.toShort
    end Format

    object Format:
        /** Decodes a wire format code. Unrecognised codes fall back to Text. */
        private[kyo] def fromCode(code: Short): Format =
            if code == 1 then Binary else Text
    end Format

    /** Emits a value's columns as bind parameters, one method call per column.
      *
      * All methods are synchronous and return plain `Unit`. Unsupported encode operations are signalled by throwing
      * [[kyo.SqlUnsupportedException]], which is [[kyo.KyoException]]-derived and converted to a typed `Abort` at the transport boundary.
      * The constructor `frame` field supplies the [[kyo.Frame]] used when constructing those exceptions.
      *
      * [[extension]] is the escape for a type only one dialect owns, such as Postgres `hstore`. Its [[SqlCodec.Writer.Payload]] carries the
      * owning dialect, so a writer for another dialect rejects the value with [[kyo.SqlUnsupportedTypeOnBackendException]] instead of
      * emitting bytes the server cannot read.
      */
    abstract class Writer(val frame: Frame):

        // --- Scalar primitives ---

        def string(value: String): Unit
        def int(value: Int): Unit
        def long(value: Long): Unit
        def float(value: Float): Unit
        def double(value: Double): Unit
        def boolean(value: Boolean): Unit
        def short(value: Short): Unit
        def byte(value: Byte): Unit
        def char(value: Char): Unit
        def bigDecimal(value: BigDecimal): Unit
        def bigInt(value: BigInt): Unit
        def bytes(value: Span[Byte]): Unit
        def instant(value: java.time.Instant): Unit
        def duration(value: java.time.Duration): Unit

        /** Emits a NULL column. */
        def nil(): Unit

        // --- SQL type vocabulary ---

        /** Emits a JSON document, given as its text form. [[SqlType.Type.Json]]. */
        def json(text: String): Unit

        /** Emits a UUID. [[SqlType.Type.Uuid]]. */
        def uuid(value: java.util.UUID): Unit

        /** Emits a calendar date with no time part. [[SqlType.Type.Date]]. */
        def date(value: java.time.LocalDate): Unit

        /** Emits a time of day with no date and no offset. [[SqlType.Type.Time]]. */
        def time(value: java.time.LocalTime): Unit

        /** Emits a time of day carrying its UTC offset. [[SqlType.Type.TimeWithOffset]]. */
        def timeWithOffset(value: java.time.OffsetTime): Unit

        /** Emits a wall-clock date and time with no zone attached. [[SqlType.Type.DateTime]]. */
        def dateTime(value: java.time.LocalDateTime): Unit

        /** Emits a span counted in calendar units rather than a fixed number of seconds. [[SqlType.Type.CalendarInterval]]. */
        def calendarInterval(value: java.time.Period): Unit

        /** Emits a one-dimensional array of integers. [[SqlType.Type.Array]] of [[SqlType.Type.Int]]. */
        def arrayOfInt(values: Chunk[Int]): Unit

        /** Emits a one-dimensional array of strings. [[SqlType.Type.Array]] of [[SqlType.Type.Text]]. */
        def arrayOfString(values: Chunk[String]): Unit

        /** Emits a one-dimensional array of JSON documents, each given as its text form. [[SqlType.Type.Array]] of
          * [[SqlType.Type.Json]].
          */
        def arrayOfJson(values: Chunk[String]): Unit

        /** Emits a value of a type a single dialect owns, already encoded in that dialect's wire form.
          *
          * @throws kyo.SqlUnsupportedTypeOnBackendException
          *   when `payload.dialect` is not the dialect this writer emits for.
          */
        def extension(payload: SqlCodec.Writer.Payload): Unit

        /** Encodes `value` as the bytes this writer's dialect uses for one column of `column`, in `format`, without emitting a bind
          * parameter.
          *
          * Composite extension payloads carry their element bytes inline: a PostgreSQL range interleaves `int32 length + element bytes` per
          * end. Core can build such a payload only by asking the active writer for a value's bytes, because a column codec hands values to
          * a writer rather than yielding bytes. Routing the request through the writer is what lets that composition live in
          * backend-agnostic core without naming a wire type, and it generalizes past ranges to arrays of arbitrary element types and to
          * composites.
          *
          * `typeName` names the element's Scala type for the diagnostics a composite payload raises. The caller states `format` because the
          * composite's byte layout fixes it: a binary range holds binary elements.
          *
          * @throws kyo.SqlUnsupportedElementFormatException
          *   when this writer cannot encode the value in `format`.
          * @throws kyo.SqlUnsupportedAbsentElementException
          *   when the value is absent, which the composite wire form has no way to carry.
          */
        def encodeElement[A](column: SqlSchema.Column[A], value: A, typeName: String, format: SqlCodec.Format): Span[Byte]

        /** Validates and returns the bytes of the single column `params` encodes, for the [[encodeElement]] of a backend writer.
          *
          * The backend produces `params` with its own writer and passes them here with the two hooks that vary between dialects: `encoded`
          * reads a param's encoded bytes, and `formatMatches` decides whether the param's wire form is the one the caller asked for. More
          * than one emitted column, an absent value, and a format the writer cannot meet are each refused with the typed leaf the composite
          * wire form has no way to carry. `typeName` names the element's Scala type in those refusals.
          */
        final private[kyo] def encodeSingleElement[P](
            typeName: String,
            params: Chunk[P],
            format: SqlCodec.Format,
            dialectId: Idiom.Id
        )(encoded: P => Maybe[Span[Byte]], formatMatches: P => Boolean)(using Frame): Span[Byte] =
            if params.size != 1 then
                throw SqlUnsupportedMultiColumnElementException(typeName, params.size)
            end if
            // Absence is checked before the format: an absent element has no bytes in either format, so reporting it as a
            // format refusal would name the backend for something no flavor's composite wire form can express.
            val head = params.head
            encoded(head) match
                case Maybe.Absent => throw SqlUnsupportedAbsentElementException(typeName)
                case Maybe.Present(bytes) =>
                    if !formatMatches(head) then
                        throw SqlUnsupportedElementFormatException(typeName, format, dialectId)
                    else bytes
            end match
        end encodeSingleElement

    end Writer

    object Writer:

        /** A dialect-owned value together with the dialect that owns it.
          *
          * @param dialect
          *   the dialect whose wire form `bytes` is encoded in, and the only dialect that accepts the payload
          * @param typeName
          *   the dialect's own name for the type, used to resolve wire metadata and to name the type in a rejection
          * @param format
          *   the wire form `bytes` is encoded in. Stated by the payload's author rather than negotiated, so a backend whose protocol offers
          *   only the other form refuses the payload instead of sending bytes the server reads as the wrong value
          * @param bytes
          *   the value encoded in the owning dialect's wire form
          */
        final case class Payload(dialect: Idiom.Id, typeName: String, format: SqlCodec.Format, bytes: Span[Byte])

    end Writer

    /** Consumes a row's columns positionally, one method call per column.
      *
      * The read counterpart of [[SqlCodec.Writer]]: each scalar primitive and each `next*` method consumes the next column and decodes it
      * from whatever wire form the backend chose for that type. A codec pairs `w.dateTime(v)` with `r.nextDateTime()` and works against
      * either backend.
      *
      * The null contract is exact and both backends implement it: [[isNil]] returns whether the current column is NULL and, when it is,
      * CONSUMES it; on a non-null column it leaves the cursor for the value read that follows. [[skip]] consumes one column unread.
      *
      * All methods are synchronous and return plain values. Decode failures are signalled by throwing [[kyo.SqlDecodeException]];
      * unsupported operations by throwing [[kyo.SqlUnsupportedException]]. Both are converted to a typed `Abort` at the transport
      * boundary. The constructor `frame` field supplies the [[kyo.Frame]] used when constructing those exceptions.
      */
    abstract class Reader(val frame: Frame):

        // --- Scalar primitives ---

        def string(): String
        def int(): Int
        def long(): Long
        def float(): Float
        def double(): Double
        def boolean(): Boolean
        def short(): Short
        def byte(): Byte
        def char(): Char
        def bigDecimal(): BigDecimal
        def bigInt(): BigInt
        def bytes(): Span[Byte]
        def instant(): java.time.Instant
        def duration(): java.time.Duration

        /** Whether the current column is NULL, consuming it when it is. See the class scaladoc for the exact contract. */
        def isNil(): Boolean

        /** Consumes one column unread. */
        def skip(): Unit

        // --- SQL type vocabulary ---

        /** Reads the next column as a JSON document, returning its text form. */
        def nextJson(): String

        /** Reads the next column as a UUID. */
        def nextUuid(): java.util.UUID

        /** Reads the next column as a calendar date with no time part. */
        def nextDate(): java.time.LocalDate

        /** Reads the next column as a time of day with no date and no offset. */
        def nextTime(): java.time.LocalTime

        /** Reads the next column as a time of day carrying its UTC offset. */
        def nextTimeWithOffset(): java.time.OffsetTime

        /** Reads the next column as a wall-clock date and time with no zone attached. */
        def nextDateTime(): java.time.LocalDateTime

        /** Reads the next column as a span counted in calendar units. */
        def nextCalendarInterval(): java.time.Period

        /** Reads the next column as a one-dimensional array of integers. */
        def nextArrayOfInt(): Chunk[Int]

        /** Reads the next column as a one-dimensional array of strings. */
        def nextArrayOfString(): Chunk[String]

        /** Reads the next column as a one-dimensional array of JSON documents, each returned as its text form. */
        def nextArrayOfJson(): Chunk[String]

        /** Reads the next column as a value of a type a single dialect owns, returning the bytes in that dialect's wire form together with
          * the format they are in.
          *
          * The carrier holds two coordinates because the caller supplied the other two: a read closure knows the dialect and the type name
          * it asked for, and learns the format and the bytes.
          *
          * @param dialect
          *   the dialect that owns the type
          * @param typeName
          *   the dialect's own name for the type, used to name the type in a rejection
          * @throws kyo.SqlUnsupportedTypeOnBackendException
          *   when `dialect` is not the dialect this reader decodes for.
          */
        def nextExtension(dialect: Idiom.Id, typeName: String): SqlCodec.Reader.Extension

        /** Decodes one column's worth of `column` from `bytes`, which are in `format`, the inverse of [[SqlCodec.Writer.encodeElement]].
          *
          * Reads a value that arrived nested inside another value's payload, a range end being the case that needs it. The format is the
          * enclosing payload's, not the reader's current column's, which is why the caller states it. A decode failure is thrown rather
          * than returned, as everywhere in the reader contract.
          */
        def decodeElement[A](column: SqlSchema.Column[A], bytes: Span[Byte], format: SqlCodec.Format): A

        /** Which row field the column at `index` belongs to, resolved against the row codec's `fieldNames`.
          *
          * The default is positional. A reader constructed with a field matcher (a whole-row decode over named result columns) overrides
          * this so a by-name decode survives a column order the row type did not declare. A return outside `names` means "no match";
          * the row codec falls back to the positional slot.
          */
        private[kyo] def fieldIndex(index: Int, names: Chunk[String]): Int = index

        /** Verifies the input is fully consumed, when the reader can know. Default no-op. */
        private[kyo] def requireEndOfInput(): Unit = ()

    end Reader

    object Reader:

        /** The bytes of one extension column together with the wire format they are in.
          *
          * @param format
          *   the wire form the server sent the column in
          * @param bytes
          *   the column's bytes, in the owning dialect's wire form
          */
        final case class Extension(format: SqlCodec.Format, bytes: Span[Byte]) derives CanEqual

    end Reader

end SqlCodec
