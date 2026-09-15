package kyo.internal.postgres

import kyo.<
import kyo.Abort
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeColumnAbsentException
import kyo.SqlDecodeColumnNotFoundException
import kyo.SqlDecodeColumnNotRenderableException
import kyo.SqlDecodeColumnOutOfBoundsException
import kyo.SqlDecodeException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.SqlValue
import kyo.bug
import kyo.internal.SqlPositionalRowCodec
import kyo.internal.SqlValueRender
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** The PostgreSQL backend's [[SqlRow.Codec]]: decodes a row's columns through [[PostgresRowReader]].
  *
  * Carries the wire format the server used for the result set that produced the row, which is the one piece of decode context a PostgreSQL row
  * needs beyond its own bytes. Being a case class, two codecs for the same format are equal, so [[SqlRow]] equality stays format-sensitive.
  *
  * @param format
  *   the wire format of every column in the rows this codec decodes
  */
final private[kyo] case class PostgresRowCodec(format: Format) extends SqlPositionalRowCodec:

    def newReader(sliced: SqlRow, matchesFieldAt: Maybe[(Int, String) => Boolean])(using Frame): SqlCodec.Reader =
        new PostgresRowReader(sliced, format, matchesFieldAt)

    override def columnKind(typeToken: Int): SqlRow.ColumnKind =
        PostgresRowCodec.kindOf(typeToken)

    override def typeName(typeToken: Int): Maybe[String] =
        PostgresRowCodec.nameOf(typeToken)

    /** A `float4` column is the narrower of the two widths this backend maps to one kind. */
    override private[kyo] def isSingleWidthFloat(typeToken: Int): Boolean =
        typeToken == PostgresRowCodec.float4Token

    /** Reads a column into the neutral value it holds, under BOTH wire formats.
      *
      * The kinds routed here are the ones whose wire this backend reads itself: a date carries an era, a time reaches 24:00:00, an interval
      * carries a calendar and a time part at once, and each has special values no Scala type holds. Everything else the shared codec reads.
      *
      * Nothing is passed through by wire format: the text protocol's bytes are the server's rendering, chosen by settings this connection is
      * never told about, so each decoder parses and re-renders.
      */
    override def columnValue(row: SqlRow, idx: Int)(using Frame): SqlValue < Abort[SqlDecodeException] =
        val typeToken = row.columns(idx).typeToken
        decoderFor(typeToken) match
            case Maybe.Present(decoder) =>
                PostgresRowCodec.columnDecoded[SqlValue](row, idx)(using summon[Frame], decoder)
            case Maybe.Absent => super.columnValue(row, idx)
        end match
    end columnValue

    /** The decoder that renders a value of `typeToken`, or [[Maybe.Absent]] for a type the shared codec renders from a neutral read.
      *
      * Keyed by type rather than by column, so an array's elements go through exactly the rendering their own type would get as a column.
      */
    private def decoderFor(typeToken: Int): Maybe[PostgresDecoder[SqlValue]] =
        import SqlRow.ColumnKind
        // An `inet` is a wire struct with no Scala type here at all, keyed by token rather than by kind.
        if typeToken == PostgresRowCodec.inetToken then Maybe(PostgresDecoder.inetValue)
        else
            columnKind(typeToken) match
                case ColumnKind.Bool           => Maybe(PostgresDecoder.boolValue)
                case ColumnKind.Date           => Maybe(PostgresDecoder.dateValue)
                case ColumnKind.DateTime       => Maybe(PostgresDecoder.timestampValue)
                case ColumnKind.Timestamp      => Maybe(PostgresDecoder.timestamptzValue)
                case ColumnKind.Time           => Maybe(PostgresDecoder.timeValue)
                case ColumnKind.TimeWithOffset => Maybe(PostgresDecoder.timetzValue)
                case ColumnKind.Decimal        => Maybe(PostgresDecoder.numericValue)
                case ColumnKind.Interval       => Maybe(PostgresDecoder.intervalValue)
                case ColumnKind.Float          =>
                    // The two widths share a kind and do not render alike, and reading the narrower one at the wider
                    // type widens it first: 0.1 becomes 0.10000000149011612.
                    if typeToken == PostgresRowCodec.float4Token then Maybe(PostgresDecoder.float4Value)
                    else Maybe(PostgresDecoder.float8Value)
                case ColumnKind.Integer => Maybe(PostgresDecoder.integerValue)
                case ColumnKind.Uuid    => Maybe(PostgresDecoder.uuidValue)
                case ColumnKind.Bytes   => Maybe(PostgresDecoder.byteaValue)
                case ColumnKind.Array   => Maybe(arrayValue)
                // Text is its own rendering under both formats and Json needs only the jsonb version byte stripped.
                // The shared codec reaches the same answers for a scalar column; they are named here because an ARRAY
                // element has no shared-codec path and would otherwise have no decoder at all.
                case ColumnKind.Text => Maybe(PostgresDecoder.textValue)
                case ColumnKind.Json => Maybe(PostgresDecoder.jsonValue)
                // Unknown has no rendering, which the shared codec settles by refusing.
                case _ => Maybe.empty
        end if
    end decoderFor

    /** Reads an array by reading each element at its own element type.
      *
      * Unlike the typed array reads there is no single Scala element type to agree on, so each element goes through the same dispatch its own
      * type would get as a column. An element type this module does not render makes the whole array unrenderable, which is the answer the
      * scalar column of that type already gets. An absent element stays absent, which the typed reads refuse because no Scala element type
      * holds one.
      */
    private lazy val arrayValue: PostgresDecoder[SqlValue] = new PostgresDecoder[SqlValue]:
        val oids: Set[Int] = Set.empty
        def read(format: Format, bytes: Span[Byte], columnOid: Int)(using frame: Frame): SqlValue =
            val arr   = new PostgresArrayReader(bytes, format, frame)
            val count = arr.openArray()
            // Only the BINARY header names an element type; a text rendering names none, so the column's own type
            // supplies it. The header wins where it spoke, in case it disagrees.
            val elemOid =
                if arr.elementOid != PostgresEncoder.OID_UNSPECIFIED then arr.elementOid
                else
                    PostgresRowCodec.elementOidOf(columnOid).getOrElse {
                        throw SqlDecodeColumnNotRenderableException(
                            s"element of ${PostgresRowCodec.nameOf(columnOid).getOrElse("array")}",
                            PostgresRowCodec.nameOf(columnOid)
                        )
                    }
            val decoder = decoderFor(elemOid).getOrElse {
                // An unrenderable element type makes the whole array unrenderable, the answer the scalar column gets.
                throw SqlDecodeColumnNotRenderableException(
                    s"element of ${PostgresRowCodec.nameOf(columnOid).getOrElse("array")}",
                    PostgresRowCodec.nameOf(elemOid)
                )
            }
            val elemForm = arr.elementFormat
            val builder  = Chunk.newBuilder[Maybe[SqlValue]]
            var i        = 0
            while i < count do
                arr.nextElement() match
                    case Maybe.Present(elemBytes) => builder += Maybe(decoder.read(elemForm, elemBytes, elemOid))
                    case Maybe.Absent             => builder += Maybe.empty
                end match
                i += 1
            end while
            SqlValue.Elements(builder.result())
        end read

end PostgresRowCodec

private[kyo] object PostgresRowCodec:

    import kyo.internal.postgres.types.PostgresEncoder.*

    /** What this backend knows about one column type: how PostgreSQL spells it, the neutral kind it maps to, and whether reading it as
      * text is a conversion rather than a reinterpretation.
      *
      * @param name
      *   the PostgreSQL spelling, for [[kyo.SqlRow.columnTypeName]]
      * @param kind
      *   the neutral kind, for [[kyo.SqlRow.columnKind]]
      * @param textReadable
      *   whether the column's bytes are the value's text rendering under every wire format, so a `String` read of it returns the value
      *   rather than the protocol buffer
      */
    final private case class TypeInfo(name: String, kind: SqlRow.ColumnKind, textReadable: Boolean)

    /** Every OID this backend can name.
      *
      * One table, so the three answers a caller gets about a column cannot drift apart: [[kyo.SqlRow.columnTypeName]],
      * [[kyo.SqlRow.columnKind]], and whether a text decode of it is refused. An OID absent from it answers `Absent`,
      * [[kyo.SqlRow.ColumnKind.Unknown]], and is NOT refused, which is the honest report for the dynamic OIDs (`citext`, an enum type, a
      * domain) whose values this connection cannot resolve to a type. Its two fates differ: readable as a `String`, not renderable by
      * [[kyo.SqlRow.text]], which refuses every `Unknown` kind.
      *
      * `textReadable` is a per-type fact rather than one derived from `kind`, because the kind does not decide it: `json` and `jsonb`
      * are both [[kyo.SqlRow.ColumnKind.Json]], and `json` carries the document text on the wire while `jsonb` prefixes it with a version
      * byte. Deriving the refusal from the kind would silently admit `jsonb`.
      *
      * `varchar` (1043) and `bpchar` (1042) are spelled as literals here, as they are in the decoders, because they have no
      * [[kyo.internal.postgres.types.PostgresEncoder]] constant: nothing encodes to them, since a `String` parameter goes out as `text`.
      */
    private val types: Map[Int, TypeInfo] = Map(
        OID_BOOL        -> TypeInfo("bool", SqlRow.ColumnKind.Bool, textReadable = false),
        OID_BYTEA       -> TypeInfo("bytea", SqlRow.ColumnKind.Bytes, textReadable = false),
        OID_INT2        -> TypeInfo("int2", SqlRow.ColumnKind.Integer, textReadable = false),
        OID_INT4        -> TypeInfo("int4", SqlRow.ColumnKind.Integer, textReadable = false),
        OID_INT8        -> TypeInfo("int8", SqlRow.ColumnKind.Integer, textReadable = false),
        OID_FLOAT4      -> TypeInfo("float4", SqlRow.ColumnKind.Float, textReadable = false),
        OID_FLOAT8      -> TypeInfo("float8", SqlRow.ColumnKind.Float, textReadable = false),
        OID_NUMERIC     -> TypeInfo("numeric", SqlRow.ColumnKind.Decimal, textReadable = false),
        OID_TEXT        -> TypeInfo("text", SqlRow.ColumnKind.Text, textReadable = true),
        1043            -> TypeInfo("varchar", SqlRow.ColumnKind.Text, textReadable = true),
        1042            -> TypeInfo("bpchar", SqlRow.ColumnKind.Text, textReadable = true),
        OID_JSON        -> TypeInfo("json", SqlRow.ColumnKind.Json, textReadable = true),
        OID_JSONB       -> TypeInfo("jsonb", SqlRow.ColumnKind.Json, textReadable = false),
        OID_UUID        -> TypeInfo("uuid", SqlRow.ColumnKind.Uuid, textReadable = false),
        OID_DATE        -> TypeInfo("date", SqlRow.ColumnKind.Date, textReadable = false),
        OID_TIME        -> TypeInfo("time", SqlRow.ColumnKind.Time, textReadable = false),
        OID_TIMETZ      -> TypeInfo("timetz", SqlRow.ColumnKind.TimeWithOffset, textReadable = false),
        OID_TIMESTAMP   -> TypeInfo("timestamp", SqlRow.ColumnKind.DateTime, textReadable = false),
        OID_TIMESTAMPTZ -> TypeInfo("timestamptz", SqlRow.ColumnKind.Timestamp, textReadable = false),
        OID_INTERVAL    -> TypeInfo("interval", SqlRow.ColumnKind.Interval, textReadable = false),
        OID_INET        -> TypeInfo("inet", SqlRow.ColumnKind.Unknown, textReadable = false),
        OID_INT4_ARRAY  -> TypeInfo("int4[]", SqlRow.ColumnKind.Array, textReadable = false),
        OID_TEXT_ARRAY  -> TypeInfo("text[]", SqlRow.ColumnKind.Array, textReadable = false),
        OID_JSONB_ARRAY -> TypeInfo("jsonb[]", SqlRow.ColumnKind.Array, textReadable = false),

        // The text-shaped built-ins. Named so a caller reading a catalog query gets a kind and a name for them, and marked readable
        // because each one's binary form IS its characters: `name` is a fixed-width string, `"char"` is the single byte, `xml` is the
        // document. Leaving them out would have left them unnamed rather than refused, since an unnamed OID is not refused.
        19  -> TypeInfo("name", SqlRow.ColumnKind.Text, textReadable = true),
        18  -> TypeInfo("char", SqlRow.ColumnKind.Text, textReadable = true),
        142 -> TypeInfo("xml", SqlRow.ColumnKind.Text, textReadable = true),

        // Struct-typed built-ins whose binary form is not their rendering. Each has a fixed OID this module already knows elsewhere:
        // `PostgresParamWriter` maps `cidr`, `macaddr` and the six ranges by name, so an unknown-token argument never covered them.
        26  -> TypeInfo("oid", SqlRow.ColumnKind.Integer, textReadable = false),
        650 -> TypeInfo("cidr", SqlRow.ColumnKind.Unknown, textReadable = false),
        774 -> TypeInfo("macaddr8", SqlRow.ColumnKind.Unknown, textReadable = false),
        // Named, but deliberately NOT Decimal: money is an int8 of the smallest currency unit rather than the
        // numeric struct, so a Decimal kind routed `text` into the numeric renderer, which read the cents as a
        // numeric header and answered `0E-100` for $1.00. Its own rendering is locale-chosen (`lc_monetary`
        // supplies the symbol and separators, and the server does not report that setting to the connection), so
        // there is no kind here whose renderer would be right. Unknown is the honest answer, and it makes `text`
        // REFUSE the column under both wire formats rather than answer one protocol's bytes.
        790  -> TypeInfo("money", SqlRow.ColumnKind.Unknown, textReadable = false),
        829  -> TypeInfo("macaddr", SqlRow.ColumnKind.Unknown, textReadable = false),
        1560 -> TypeInfo("bit", SqlRow.ColumnKind.Unknown, textReadable = false),
        1562 -> TypeInfo("varbit", SqlRow.ColumnKind.Unknown, textReadable = false),
        3614 -> TypeInfo("tsvector", SqlRow.ColumnKind.Unknown, textReadable = false),
        3615 -> TypeInfo("tsquery", SqlRow.ColumnKind.Unknown, textReadable = false),

        // The geometric family, all fixed-layout float8 structs.
        600 -> TypeInfo("point", SqlRow.ColumnKind.Unknown, textReadable = false),
        601 -> TypeInfo("lseg", SqlRow.ColumnKind.Unknown, textReadable = false),
        602 -> TypeInfo("path", SqlRow.ColumnKind.Unknown, textReadable = false),
        603 -> TypeInfo("box", SqlRow.ColumnKind.Unknown, textReadable = false),
        604 -> TypeInfo("polygon", SqlRow.ColumnKind.Unknown, textReadable = false),
        628 -> TypeInfo("line", SqlRow.ColumnKind.Unknown, textReadable = false),
        718 -> TypeInfo("circle", SqlRow.ColumnKind.Unknown, textReadable = false),

        // The ranges, each a flag byte followed by its bounds.
        3904 -> TypeInfo("int4range", SqlRow.ColumnKind.Unknown, textReadable = false),
        3906 -> TypeInfo("numrange", SqlRow.ColumnKind.Unknown, textReadable = false),
        3908 -> TypeInfo("tsrange", SqlRow.ColumnKind.Unknown, textReadable = false),
        3910 -> TypeInfo("tstzrange", SqlRow.ColumnKind.Unknown, textReadable = false),
        3912 -> TypeInfo("daterange", SqlRow.ColumnKind.Unknown, textReadable = false),
        3926 -> TypeInfo("int8range", SqlRow.ColumnKind.Unknown, textReadable = false),

        // The remaining array types. Every array's binary form is the same header-plus-elements struct whatever it holds, so the three
        // this module can encode were never the only ones a SELECT could return.
        199  -> TypeInfo("json[]", SqlRow.ColumnKind.Array, textReadable = false),
        651  -> TypeInfo("cidr[]", SqlRow.ColumnKind.Array, textReadable = false),
        791  -> TypeInfo("money[]", SqlRow.ColumnKind.Array, textReadable = false),
        1000 -> TypeInfo("bool[]", SqlRow.ColumnKind.Array, textReadable = false),
        1001 -> TypeInfo("bytea[]", SqlRow.ColumnKind.Array, textReadable = false),
        1003 -> TypeInfo("name[]", SqlRow.ColumnKind.Array, textReadable = false),
        1005 -> TypeInfo("int2[]", SqlRow.ColumnKind.Array, textReadable = false),
        1014 -> TypeInfo("bpchar[]", SqlRow.ColumnKind.Array, textReadable = false),
        1015 -> TypeInfo("varchar[]", SqlRow.ColumnKind.Array, textReadable = false),
        1016 -> TypeInfo("int8[]", SqlRow.ColumnKind.Array, textReadable = false),
        1021 -> TypeInfo("float4[]", SqlRow.ColumnKind.Array, textReadable = false),
        1022 -> TypeInfo("float8[]", SqlRow.ColumnKind.Array, textReadable = false),
        1028 -> TypeInfo("oid[]", SqlRow.ColumnKind.Array, textReadable = false),
        1040 -> TypeInfo("macaddr[]", SqlRow.ColumnKind.Array, textReadable = false),
        1041 -> TypeInfo("inet[]", SqlRow.ColumnKind.Array, textReadable = false),
        1115 -> TypeInfo("timestamp[]", SqlRow.ColumnKind.Array, textReadable = false),
        1182 -> TypeInfo("date[]", SqlRow.ColumnKind.Array, textReadable = false),
        1183 -> TypeInfo("time[]", SqlRow.ColumnKind.Array, textReadable = false),
        1185 -> TypeInfo("timestamptz[]", SqlRow.ColumnKind.Array, textReadable = false),
        1187 -> TypeInfo("interval[]", SqlRow.ColumnKind.Array, textReadable = false),
        1231 -> TypeInfo("numeric[]", SqlRow.ColumnKind.Array, textReadable = false),
        1270 -> TypeInfo("timetz[]", SqlRow.ColumnKind.Array, textReadable = false),
        2951 -> TypeInfo("uuid[]", SqlRow.ColumnKind.Array, textReadable = false)
    )

    /** The PostgreSQL name of `columnOid`, for any type this backend names. Absent otherwise, which is what says an OID carries no known
      * meaning and so is not evidence of anything.
      */
    private[postgres] def typeNameOf(columnOid: Int): Maybe[String] =
        nameOf(columnOid)

    /** The PostgreSQL name of `columnOid` when a text read of it would reinterpret its bytes rather than render its value.
      *
      * Absent for a text-readable type and for an OID this backend cannot name, which are the two cases a text read is allowed to
      * proceed on. This is what [[kyo.internal.postgres.types.PostgresDecoder.requireTextColumn]] refuses against.
      */
    private[postgres] def nonTextColumnType(columnOid: Int): Maybe[String] =
        types.get(columnOid) match
            case Some(info) if !info.textReadable => Maybe(info.name)
            case _                                => Maybe.empty

    /** The kind and name lookups as flat arrays indexed by the type token, derived from [[types]] so a type added there reaches both.
      *
      * Arrays rather than the map, because these are read once per column per row and a `Map[Int, V]` boxes its key on every lookup.
      */
    private val kindByToken: Array[SqlRow.ColumnKind] =
        val arr = Array.fill[SqlRow.ColumnKind](types.keys.max + 1)(SqlRow.ColumnKind.Unknown)
        types.foreach((oid, info) => arr(oid) = info.kind)
        arr
    end kindByToken

    private val nameByToken: Array[Maybe[String]] =
        val arr = Array.fill[Maybe[String]](types.keys.max + 1)(Maybe.empty)
        types.foreach((oid, info) => arr(oid) = Maybe(info.name))
        arr
    end nameByToken

    private[postgres] def kindOf(typeToken: Int): SqlRow.ColumnKind =
        if typeToken < 0 || typeToken >= kindByToken.length then SqlRow.ColumnKind.Unknown
        else kindByToken(typeToken)

    private[postgres] def nameOf(typeToken: Int): Maybe[String] =
        if typeToken < 0 || typeToken >= nameByToken.length then Maybe.empty
        else nameByToken(typeToken)

    /** The element type each array type holds.
      *
      * A text rendering is just `{...}` and names no element type, so without this an element under the simple protocol has nothing to
      * dispatch on. Fixed protocol data, not a catalog lookup: built-in OIDs do not vary by installation.
      */
    private val elementOids: Map[Int, Int] = Map(
        OID_INT4_ARRAY  -> OID_INT4,
        OID_TEXT_ARRAY  -> OID_TEXT,
        OID_JSONB_ARRAY -> OID_JSONB,
        199             -> OID_JSON,
        651             -> 650,
        791             -> 790,
        1000            -> OID_BOOL,
        1001            -> OID_BYTEA,
        1003            -> 19,
        1005            -> OID_INT2,
        1014            -> 1042,
        1015            -> 1043,
        1016            -> OID_INT8,
        1021            -> OID_FLOAT4,
        1022            -> OID_FLOAT8,
        1028            -> 26,
        1040            -> 829,
        1041            -> OID_INET,
        1115            -> OID_TIMESTAMP,
        1182            -> OID_DATE,
        1183            -> OID_TIME,
        1185            -> OID_TIMESTAMPTZ,
        1187            -> OID_INTERVAL,
        1231            -> OID_NUMERIC,
        1270            -> OID_TIMETZ,
        2951            -> OID_UUID
    )

    /** The element type of the array type `columnOid`, or [[Maybe.Absent]] for an array type this module cannot name. */
    private[postgres] def elementOidOf(columnOid: Int): Maybe[Int] =
        Maybe.fromOption(elementOids.get(columnOid))

    /** The `inet` OID, which `text` renders specially. Named here because no neutral [[kyo.SqlRow.ColumnKind]] describes an address, so
      * the type token is what the rendering keys on rather than the kind every other column is dispatched by.
      */
    private val inetToken: Int = OID_INET

    /** The `float4` OID, which `text` renders apart from `float8` despite the two sharing a neutral kind: the kind says how wide a Scala
      * type reads them, and reading a `float4` at `Double` widens the value before it is rendered.
      */
    private[postgres] val float4Token: Int = OID_FLOAT4

    /** Builds a [[SqlRow]] from what a PostgreSQL result-set message carries: the column bytes, the `RowDescription` fields, and the wire
      * format the Bind message asked for.
      */
    private[kyo] def row(
        values: Chunk[Maybe[Span[Byte]]],
        fields: Chunk[FieldDescription],
        format: Format = Format.Text
    ): SqlRow =
        new SqlRow(values, fields.map(f => SqlRow.Column(f.name, f.dataType)), PostgresRowCodec(format))

    /** The wire format the row's own codec carries.
      *
      * A row a PostgreSQL exchange produced always carries a [[PostgresRowCodec]]; anything else reaching a PostgreSQL decode path is a wiring
      * error, not a runtime condition.
      */
    private[kyo] def formatOf(row: SqlRow): Format =
        row.codec match
            case codec: PostgresRowCodec => codec.format
            case other                   => bug(s"a PostgreSQL decoder cannot read a row decoded by $other")

    /** Decodes one column with an explicit [[PostgresDecoder]], resolved by the caller rather than by the schema layer.
      *
      * The escape hatch for a column whose type has no `Schema`, or whose wire form the caller wants to decode itself. Aborts when the
      * index is out of bounds, the column is NULL, or the decoder fails. A decoder failure `NonFatal` excludes, a `VirtualMachineError` or an
      * interrupt among them, arrives as a panic on this same channel rather than as a `SqlDecodeException` a caller could recover from by
      * type.
      */
    private[kyo] def columnDecoded[A](row: SqlRow, idx: Int)(using Frame, PostgresDecoder[A]): A < Abort[SqlDecodeException] =
        if idx < 0 || idx >= row.size then
            Abort.fail(SqlDecodeColumnOutOfBoundsException(idx, row.size))
        else
            row.column(idx) match
                case Maybe.Absent         => Abort.fail(SqlDecodeColumnAbsentException(idx))
                case Maybe.Present(bytes) =>
                    // The column's own OID goes to the decoder: a numeric decoder resolves the wire width from it, and
                    // an explicitly-summoned decoder is exactly where a caller's Scala type and the column's type are
                    // most likely to differ.
                    //
                    // Classification is the shared helper's, so this entry point and the schema-driven `read` above
                    // report one decoder failure the same way. What this one adds is the column: it has an index where
                    // a whole-row decode has none, and naming it is why this entry point exists separately at all.
                    SqlRow.Codec.catchingColumn(Maybe.Present(idx)) {
                        summon[PostgresDecoder[A]].read(formatOf(row), bytes, row.columns(idx).typeToken)
                    }

    /** Decodes the column named `name` with an explicit [[PostgresDecoder]]. */
    private[kyo] def columnDecoded[A](row: SqlRow, name: String)(using Frame, PostgresDecoder[A]): A < Abort[SqlDecodeException] =
        val idx = row.columnNames.indexWhere(_ == name)
        if idx < 0 then Abort.fail(SqlDecodeColumnNotFoundException(name, row.columnNames))
        else columnDecoded[A](row, idx)
    end columnDecoded

end PostgresRowCodec
