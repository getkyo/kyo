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
import kyo.SqlDecodeColumnOutOfBoundsException
import kyo.SqlDecodeException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.bug
import kyo.internal.SqlPositionalRowCodec
import kyo.internal.postgres.types.PostgresDecoder

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
        PostgresRowCodec.kinds.getOrElse(typeToken, SqlRow.ColumnKind.Unknown)

    override def typeName(typeToken: Int): Maybe[String] =
        Maybe.fromOption(PostgresRowCodec.typeNames.get(typeToken))

    /** Renders a column as the server renders it, so one stored value reads as one string whichever protocol carried the row.
      *
      * The shared codec reads each kind at the widest Scala type in its family and prints the Scala value, which agrees with the server
      * for some types and not others. It disagrees for whole column types rather than at the edges: PostgreSQL writes a bool `t`, a
      * timestamp `2026-08-25 10:00:00`, a time `10:00:00`, and a float8 1e10 `10000000000`, where Java writes `true`,
      * `2026-08-25T10:00`, `10:00`, and `1.0E10`. Under the text protocol the server already sent its rendering and the bytes are handed
      * back, so every type routed here is one whose binary rendering had to be brought into line with that.
      *
      * Two of them have no Scala type to read at all. An `interval` is free to carry both a calendar and a time part, which
      * `java.time.Duration` and `java.time.Period` each refuse half of, and it is routed under BOTH formats because which of its four
      * renderings the server writes is a session setting. An `inet` is a wire struct, which the shared fallback would answer as UTF-8.
      */
    override def text(row: SqlRow, idx: Int)(using Frame): String < Abort[SqlDecodeException] =
        import SqlRow.ColumnKind
        val typeToken = row.columns(idx).typeToken
        inline def renderWith(decoder: PostgresDecoder[String]) =
            PostgresRowCodec.columnDecoded[String](row, idx)(using summon[Frame], decoder)
        // The interval is the one routed under both formats, for the session-setting reason above.
        if columnKind(typeToken) == ColumnKind.Interval then renderWith(PostgresDecoder.intervalText)
        else if format == Format.Text then super.text(row, idx)
        else if typeToken == PostgresRowCodec.inetToken then renderWith(PostgresDecoder.inetText)
        else
            columnKind(typeToken) match
                case ColumnKind.Bool           => renderWith(PostgresDecoder.boolText)
                case ColumnKind.Date           => renderWith(PostgresDecoder.dateText)
                case ColumnKind.DateTime       => renderWith(PostgresDecoder.timestampText)
                case ColumnKind.Timestamp      => renderWith(PostgresDecoder.timestamptzText)
                case ColumnKind.Time           => renderWith(PostgresDecoder.timeText)
                case ColumnKind.TimeWithOffset => renderWith(PostgresDecoder.timetzText)
                case ColumnKind.Decimal        => renderWith(PostgresDecoder.numericText)
                case ColumnKind.Float          =>
                    // float4 and float8 share a kind, and reading a float4 at Double widens it before it is rendered:
                    // 0.1 becomes 0.10000000149011612 where the server writes 0.1.
                    if typeToken == PostgresRowCodec.float4Token then renderWith(PostgresDecoder.float4Text)
                    else renderWith(PostgresDecoder.float8Text)
                case _ => super.text(row, idx)
        end if
    end text

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
      * domain) whose values this connection cannot resolve to a type.
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
        // there is no kind here whose renderer would be right; Unknown is the honest answer and leaves `text` on
        // the documented byte fallback, which is the server's own rendering under the text protocol.
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

    /** The PostgreSQL name of `columnOid` when a text read of it would reinterpret its bytes rather than render its value.
      *
      * Absent for a text-readable type and for an OID this backend cannot name, which are the two cases a text read is allowed to
      * proceed on. This is what [[kyo.internal.postgres.types.PostgresDecoder.requireTextColumn]] refuses against.
      */
    /** The PostgreSQL name of `columnOid`, for any type this backend names. Absent otherwise, which is what says an OID carries no known
      * meaning and so is not evidence of anything.
      */
    private[postgres] def typeNameOf(columnOid: Int): Maybe[String] =
        Maybe.fromOption(typeNames.get(columnOid))

    private[postgres] def nonTextColumnType(columnOid: Int): Maybe[String] =
        types.get(columnOid) match
            case Some(info) if !info.textReadable => Maybe(info.name)
            case _                                => Maybe.empty

    private val typeNames: Map[Int, String]        = types.view.mapValues(_.name).toMap
    private val kinds: Map[Int, SqlRow.ColumnKind] = types.view.mapValues(_.kind).toMap

    /** The `inet` OID, which `text` renders specially. Named here because no neutral [[kyo.SqlRow.ColumnKind]] describes an address, so
      * the type token is what the rendering keys on rather than the kind every other column is dispatched by.
      */
    private[postgres] val inetToken: Int = OID_INET

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
