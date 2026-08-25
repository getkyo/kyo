package kyo.internal.postgres

import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlRequestDurationOverflowException
import kyo.SqlSchema
import kyo.SqlUnsupportedCustomTypeException
import kyo.SqlUnsupportedTypeOnBackendException
import kyo.internal.postgres.types.PostgresEncoder

/** Maps `Codec.Writer` primitive calls to PostgreSQL `BoundParam` instances.
  *
  * Each primitive method appends one [[BoundParam]] to an internal buffer. The OID and wire-format selection mirrors the
  * [[PostgresEncoder]] singletons exactly, so the byte output is byte-for-byte identical to calling those encoders directly.
  *
  * After all writes, retrieve the accumulated params via [[params]].
  *
  * @param registry
  *   OID lookup for custom opaque types (e.g. geometry, hstore, pgvector). Populated from `pg_type` at session startup for each name
  *   declared in [[kyo.PostgresConfig.typeNames]]. Pass [[TypeRegistry.empty]] when no custom types are needed.
  * @param binaryElements
  *   selects the binary sibling for the types whose standalone bind is text (`numeric`, and `bigInt` through it). Set only by
  *   [[encodeElement]] when a composite payload's layout demands binary elements: a standalone `numeric` parameter stays text for
  *   planner-plan parity (see [[bigDecimal]]), but an element inside a binary composite never reaches the planner as a parameter, so
  *   refusing it would leave `Range[BigDecimal]` compiling and then failing at the bind, which is the runtime support gap compiling at all
  *   is meant to rule out.
  */
final class PostgresParamWriter(registry: TypeRegistry, binaryElements: Boolean = false)(using frame: Frame)
    extends SqlCodec.Writer(frame):

    private val _params = Chunk.newBuilder[BoundParam[?]]

    /** Returns the accumulated bound parameters in the order they were written. Drains the accumulator: a second read answers empty, so
      * the caller collects once per statement.
      */
    def params: Chunk[BoundParam[?]] = _params.result()

    // --- Primitive value methods ---

    override def boolean(value: Boolean): Unit =
        _params += BoundParam(value, PostgresEncoder.boolBinary)

    override def short(value: Short): Unit =
        _params += BoundParam(value, PostgresEncoder.int2Binary)

    override def int(value: Int): Unit =
        _params += BoundParam(value, PostgresEncoder.int4Binary)

    override def long(value: Long): Unit =
        _params += BoundParam(value, PostgresEncoder.int8Binary)

    override def float(value: Float): Unit =
        _params += BoundParam(value, PostgresEncoder.float4Binary)

    override def double(value: Double): Unit =
        _params += BoundParam(value, PostgresEncoder.float8Binary)

    /** Encodes a `BigDecimal` as PostgreSQL `NUMERIC` using the **text** wire format (OID 1700, `Format.Text`).
      *
      * Encoding in the text wire format keeps the bytes identical to the static SQL renderer, which guarantees that a parameterised query and
      * an equivalent literal query produce the same planner plan. The binary form (`numericBinary`) would introduce a silent representation
      * mismatch that changes the planner plan and is difficult to detect in tests.
      *
      * Under `binaryElements` (an element of a binary composite, never a standalone parameter) the binary form is the one the payload's
      * layout demands, and the planner argument does not apply there.
      */
    override def bigDecimal(value: BigDecimal): Unit =
        _params += BoundParam(value, if binaryElements then PostgresEncoder.numericBinary else PostgresEncoder.numericText)

    override def string(value: String): Unit =
        // textText uses Format.Text (not binary); OID 25.
        _params += BoundParam(value, PostgresEncoder.textText)

    override def bytes(value: Span[Byte]): Unit =
        _params += BoundParam(value, PostgresEncoder.byteaBinary)

    override def instant(value: java.time.Instant): Unit =
        // timestamptzBinary is typed PostgresEncoder[kyo.Instant]; convert at the boundary.
        _params += BoundParam(Instant.fromJava(value), PostgresEncoder.timestamptzBinary)

    /** Encodes a `Byte` as PostgreSQL `INT2` (OID 21, binary format) by widening to `Short`.
      *
      * PostgreSQL has no single-byte scalar OID (`INT1` / `TINYINT` does not exist in the standard type system). The value is widened to
      * `Short` before encoding so that the bound param carries a valid OID. Narrowing back on read is lossless for any value in
      * `[-128, 127]`. Callers that store values outside that range in an `INT2` column will see truncation on the Scala side.
      */
    override def byte(value: Byte): Unit =
        _params += BoundParam(value.toShort, PostgresEncoder.int2Binary)

    /** Encodes a `Char` as a single-character PostgreSQL `TEXT` string (OID 25, `Format.Text`).
      *
      * PostgreSQL's `"char"` pseudo-type (OID 18) is an internal type not intended for application use. The standard `CHAR(1)` column type
      * resolves to `BPCHAR` (OID 1042) in the wire protocol and accepts a plain text string. Encoding via `textText` ensures compatibility
      * with both `TEXT` and `CHAR(1)` columns. An empty `Char` (Unicode U+0000) encodes as an empty string; a multi-char `Char` is impossible
      * in Scala (each `Char` is exactly one UTF-16 code unit).
      */
    override def char(value: Char): Unit =
        _params += BoundParam(value.toString, PostgresEncoder.textText)

    /** Encodes a `BigInt` as PostgreSQL `NUMERIC` by promoting it to `BigDecimal` first (OID 1700, `Format.Text`).
      *
      * PostgreSQL has no dedicated integer OID for arbitrary-precision integers; the closest match is `NUMERIC` (OID 1700). The promotion
      * `BigDecimal(bigInt)` is exact and lossless for all `BigInt` values. The resulting `BoundParam` is byte-for-byte identical to calling
      * `bigDecimal(BigDecimal(value))` directly, which keeps planner estimates consistent between the two call paths.
      */
    override def bigInt(value: BigInt): Unit =
        _params += BoundParam(BigDecimal(value), if binaryElements then PostgresEncoder.numericBinary else PostgresEncoder.numericText)

    override def duration(value: java.time.Duration): Unit =
        // Validate that seconds x 1_000_000 fits in Int64 before deferring to the encoder.
        // Math.multiplyExact throws ArithmeticException on overflow; wrap as a typed leaf
        // so the caller receives a typed error instead of an unchecked exception.
        try java.lang.Math.multiplyExact(value.getSeconds, 1_000_000L)
        catch
            case _: ArithmeticException =>
                given Frame = frame
                throw SqlRequestDurationOverflowException(value.getSeconds / 86_400L, "the PostgreSQL interval microsecond range")
        end try
        _params += BoundParam(value, PostgresEncoder.intervalBinary)
    end duration

    /** Writes a NULL parameter to the PostgreSQL `Bind` message.
      *
      * A NULL parameter is signalled by a length field of `-1` and carries no bytes, so the encoder's `write` is never called. Its OID is
      * still sent, in the `Parse` message, and that is the part that matters: the OID declares what type the parameter IS, so a concrete one
      * makes the server type-check the NULL against it. Declaring `INT4` for a NULL bound into a `TEXT` column is a mismatch the server
      * rejects, naming a type the caller never wrote.
      *
      * [[PostgresEncoder.nullUnspecified]] declares oid 0, the protocol's "no type named here", which leaves the target column to drive
      * inference. That is what makes an absent cell bindable at all, binding it through the OID rather than writing `NULL` into the statement
      * text, so the statement text does not vary by whether a value is present.
      */
    override def nil(): Unit =
        _params += BoundParam.nullParam(PostgresEncoder.nullUnspecified)

    // --- Built-in PostgreSQL type-name → OID map ---
    // Covers the standard scalar types. Checked before the user-supplied registry
    // so that common names like "date" and "timestamp" work without requiring them
    // in PostgresConfig.typeNames.

    private val builtinTypeOids: Map[String, Int] = Map(
        "bool"        -> 16,
        "bytea"       -> 17,
        "int2"        -> 21,
        "int4"        -> 23,
        "int8"        -> 20,
        "float4"      -> 700,
        "float8"      -> 701,
        "numeric"     -> 1700,
        "text"        -> 25,
        "date"        -> 1082,
        "time"        -> 1083,
        "timetz"      -> 1266,
        "timestamp"   -> 1114,
        "timestamptz" -> 1184,
        "interval"    -> 1186,
        "uuid"        -> 2950,
        "xml"         -> 142,
        "json"        -> 114,
        "jsonb"       -> 3802,
        "inet"        -> 869,
        "cidr"        -> 650,
        "macaddr"     -> 829,
        "_int4"       -> 1007, // int4[] array type
        "_text"       -> 1009, // text[] array type
        "_jsonb"      -> 3807, // jsonb[] array type
        // The six builtin range types. Their OIDs are fixed, unlike a CREATE TYPE ... AS RANGE type, which has
        // to be declared through PostgresConfig.typeNames. PostgresTypes.RangeKind names exactly these six.
        "int4range" -> 3904,
        "numrange"  -> 3906,
        "tsrange"   -> 3908,
        "tstzrange" -> 3910,
        "daterange" -> 3912,
        "int8range" -> 3926
    )

    /** Appends a pre-encoded payload under the OID registered for `typeName`.
      *
      * Looks up `typeName` in [[builtinTypeOids]] first, then in the session [[TypeRegistry]]. On a match, wraps `bytes` in a
      * [[BoundParam]] with the resolved OID and the supplied wire format.
      *
      * @throws SqlUnsupportedCustomTypeException
      *   if `typeName` is not found in either [[builtinTypeOids]] or the session [[TypeRegistry]]. The type must be declared in
      *   [[kyo.PostgresConfig.typeNames]] before pool initialisation.
      */
    private def named(typeName: String, bytes: Span[Byte], wireFormat: Format): Unit =
        val oidOpt = Maybe.fromOption(builtinTypeOids.get(typeName).orElse(registry.get(typeName)))
        oidOpt match
            case Present(typeOid) =>
                val enc = new PostgresEncoder[Span[Byte]]:
                    def oid: Int       = typeOid
                    def format: Format = wireFormat
                    def write(value: Span[Byte], buf: PostgresBufferWriter): Unit =
                        buf.writeBytes(value)
                _params += BoundParam(bytes, enc)
            case Absent =>
                given Frame = frame
                throw SqlUnsupportedCustomTypeException(typeName)
        end match
    end named

    /** Encodes `value` with `encoder` into a fresh buffer and returns the bytes, so a vocabulary method can hand a pre-encoded payload to
      * [[named]] under the type name whose OID the column carries.
      */
    private def encoded[A](encoder: PostgresEncoder[A], value: A): Span[Byte] =
        val buf = new PostgresBufferWriter()
        encoder.write(value, buf)
        buf.toSpan
    end encoded

    // --- SQL type vocabulary ---
    //
    // Each method names the SQL type the value IS and picks the PostgreSQL wire form for it. The type
    // names below are the `pg_type` names whose OIDs `builtinTypeOids` carries, so the emitted param
    // reaches the server tagged with the OID the column expects.

    override def json(text: String): Unit =
        named("jsonb", encoded(PostgresEncoder.jsonbBinary, text), Format.Binary)

    override def uuid(value: java.util.UUID): Unit =
        named("uuid", encoded(PostgresEncoder.uuidBinary, value), Format.Binary)

    override def date(value: java.time.LocalDate): Unit =
        named("date", encoded(PostgresEncoder.dateBinary, value), Format.Binary)

    override def time(value: java.time.LocalTime): Unit =
        named("time", encoded(PostgresEncoder.timeBinary, value), Format.Binary)

    override def timeWithOffset(value: java.time.OffsetTime): Unit =
        named("timetz", encoded(PostgresEncoder.timetzBinary, value), Format.Binary)

    override def dateTime(value: java.time.LocalDateTime): Unit =
        named("timestamp", encoded(PostgresEncoder.timestampBinary, value), Format.Binary)

    override def calendarInterval(value: java.time.Period): Unit =
        named("interval", encoded(PostgresEncoder.intervalPeriodBinary, value), Format.Binary)

    override def arrayOfInt(values: Chunk[Int]): Unit =
        named("_int4", encoded(PostgresEncoder.int4ArrayBinary, values), Format.Binary)

    override def arrayOfString(values: Chunk[String]): Unit =
        named("_text", encoded(PostgresEncoder.textArrayBinary, values), Format.Binary)

    override def arrayOfJson(values: Chunk[String]): Unit =
        named("_jsonb", encoded(PostgresEncoder.jsonbArrayBinary, values), Format.Binary)

    /** Appends a payload for a type PostgreSQL owns, already encoded in the PostgreSQL wire form the payload names.
      *
      * The payload's format reaches the Bind message as the parameter's format code, so a text-format payload is announced as text rather
      * than as the binary the server would otherwise try to read it as.
      *
      * @throws kyo.SqlUnsupportedTypeOnBackendException
      *   when the payload belongs to another dialect.
      * @throws SqlUnsupportedCustomTypeException
      *   when the payload's type name has no OID in [[builtinTypeOids]] or the session [[TypeRegistry]].
      */
    override def extension(payload: SqlCodec.Writer.Payload): Unit =
        given Frame = frame
        if payload.dialect != PostgresEncoder.dialectId then
            throw SqlUnsupportedTypeOnBackendException(payload.dialect, payload.typeName, PostgresEncoder.dialectId)
        end if
        named(payload.typeName, payload.bytes, payload.format)
    end extension

    /** Encodes one column of `schema` in `format`, for a composite payload that inlines its elements.
      *
      * A binary demand re-runs the column's write with `binaryElements` set, so the types whose standalone bind is text (`numeric`) meet
      * the demand with their binary sibling instead of being refused. A demand this writer has no encoder for in `format` is still
      * refused rather than met with bytes in the other format, which the composite's reader would parse under the format the payload
      * announced.
      */
    override def encodeElement[A](column: SqlSchema.Column[A], value: A, typeName: String, format: SqlCodec.Format): Span[Byte] =
        given Frame = frame
        val elementParams =
            PostgresParamWriter.write(column, value, registry, binaryElements = format == SqlCodec.Format.Binary)
        encodeSingleElement(typeName, elementParams, format, PostgresEncoder.dialectId)(
            _.encoded,
            _.encoder.format == format
        )
    end encodeElement

end PostgresParamWriter

object PostgresParamWriter:

    /** Encodes `value` as the PostgreSQL bind parameters `column` describes.
      *
      * One [[BoundParam]] per scalar field: structural framing methods are no-ops on [[PostgresParamWriter]], so a case class flattens into a
      * positional parameter list. This is the single write entry point for the PostgreSQL backend, used by the client backend and by the
      * wire-encoding suites alike.
      */
    private[kyo] def write[A](
        column: SqlSchema.Column[A],
        value: A,
        registry: TypeRegistry = TypeRegistry.empty,
        binaryElements: Boolean = false
    )(using
        Frame
    ): Chunk[BoundParam[?]] =
        val writer = new PostgresParamWriter(registry, binaryElements)
        column.write(value, writer)
        writer.params
    end write

end PostgresParamWriter
