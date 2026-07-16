package kyo

/** Structural metadata attached to a journaled event.
  *
  * Metadata is a map from validated dotted-path keys to structural values, stored beside the raw payload. It exists for infrastructure
  * concerns (correlation identifiers, tracing, tags) that consumers may need without decoding the payload. Values are
  * [[kyo.MetadataValue]] wrappers around [[kyo.Structure.Value]] trees with constructor-exact serialization fidelity.
  *
  * @see
  *   [[kyo.EventEnvelope]] and [[kyo.RecordedEvent]] which carry this metadata
  * @see
  *   [[kyo.MetadataKey]] for the key validation rules
  */
final case class EventMetadata(values: Map[MetadataKey, MetadataValue]) derives CanEqual

object EventMetadata:
    /** Metadata with no entries. */
    val empty: EventMetadata = EventMetadata(Map.empty)
end EventMetadata

/** A metadata value that preserves constructor identity through serialization.
  *
  * Backed by [[kyo.Structure.Value]] directly with no allocation overhead. The accompanying [[Schema]] encodes each of the ten
  * constructors as a one-field record keyed by a fixed tag string (str, int, bool, decimal, bignum, null, seq, record, entries, variant),
  * so every constructor round-trips without loss through the self-describing binary codecs the file
  * backend uses (Ion Binary by default, MsgPack when selected explicitly). This covers the three
  * constructors the kyo-schema identity codec does not preserve: [[kyo.Structure.Value.VariantCase]],
  * [[kyo.Structure.Value.MapEntries]] with all-string keys, and [[kyo.Structure.Value.BigNum]]. The
  * text codecs do not read the tag-keyed open shape back for every constructor, so the round-trip
  * guarantee is scoped to the binary metadata path configured via [[kyo.EventMetadataCodec]].
  *
  * Construct with [[MetadataValue.apply]] and project with [[MetadataValue.value]]; the opaque wrapper does not auto-convert.
  *
  * @see
  *   [[kyo.EventMetadata]] which holds a map of these values
  * @see
  *   [[kyo.MetadataKey]] for the key validation rules
  * @see
  *   [[kyo.Structure.Value]] for the full constructor vocabulary
  */
opaque type MetadataValue = Structure.Value

object MetadataValue:
    /** Wraps a [[kyo.Structure.Value]] as a metadata value. */
    def apply(value: Structure.Value): MetadataValue = value

    extension (self: MetadataValue)
        /** The underlying structural value. */
        def value: Structure.Value = self

    /** Constructor-exact codec: each of the ten [[kyo.Structure.Value]] constructors encodes as a one-field record keyed by its tag
      * name and round-trips without loss through the binary metadata codec the file backend uses (Ion Binary by default). The tag-keyed
      * open shape is not read back by every text codec, so the guarantee is scoped to the binary metadata path.
      */
    given metadataValueSchema: Schema[MetadataValue] = Schema.init[MetadataValue](
        writeFn = (v, w) => write(w, v),
        readFn = r => read(r),
        structure = Structure.Type.Open(Tag[Structure.Value].asInstanceOf[Tag[Any]])
    )

    given CanEqual[MetadataValue, MetadataValue] = CanEqual.derived

    private[kyo] def write(w: Codec.Writer, v: MetadataValue): Unit =
        w.mapStart(1)
        (v: Structure.Value) match
            case Structure.Value.Str(s) =>
                w.field("str", 0); w.string(s)
            case Structure.Value.Integer(l) =>
                w.field("int", 0); w.long(l)
            case Structure.Value.Bool(b) =>
                w.field("bool", 0); w.boolean(b)
            case Structure.Value.Decimal(d) =>
                w.field("decimal", 0); w.double(d)
            case Structure.Value.BigNum(bd) =>
                w.field("bignum", 0); w.string(bd.toString)
            case Structure.Value.Null =>
                w.field("null", 0); w.nil()
            case Structure.Value.Sequence(elements) =>
                w.field("seq", 0)
                w.arrayStart(elements.size)
                elements.foreach(e => write(w, e))
                w.arrayEnd()
            case Structure.Value.Record(fields) =>
                w.field("record", 0)
                w.mapStart(fields.size)
                fields.foreach((n, vv) =>
                    w.field(n, 0); write(w, vv)
                )
                w.mapEnd()
            case Structure.Value.MapEntries(entries) =>
                w.field("entries", 0)
                val allStringKeys = entries.forall {
                    case (Structure.Value.Str(_), _) => true
                    case _                           => false
                }
                if allStringKeys then
                    w.mapStart(entries.size)
                    entries.foreach {
                        case (Structure.Value.Str(k), vv) => w.field(k, 0); write(w, vv)
                        case _                            => ()
                    }
                    w.mapEnd()
                else
                    w.arrayStart(entries.size)
                    entries.foreach((k, vv) =>
                        w.arrayStart(2); write(w, k); write(w, vv); w.arrayEnd()
                    )
                    w.arrayEnd()
                end if
            case Structure.Value.VariantCase(name, vv) =>
                w.field("variant", 0)
                w.mapStart(2)
                w.field("name", 0); w.string(name)
                w.field("value", 0); write(w, vv)
                w.mapEnd()
            case Structure.Value.Bytes(b) =>
                w.field("bytes", 0); w.bytes(b)
            case Structure.Value.Instant(i) =>
                w.field("instant", 0); w.instant(i)
            case Structure.Value.Duration(d) =>
                w.field("duration", 0); w.duration(d)
        end match
        w.mapEnd()
    end write

    private[kyo] def read(r: Codec.Reader): MetadataValue =
        discard(r.objectStart())
        val tag = r.field()
        val node: Structure.Value = tag match
            case "str"     => Structure.Value.Str(r.string())
            case "int"     => Structure.Value.Integer(r.long())
            case "bool"    => Structure.Value.Bool(r.boolean())
            case "decimal" => Structure.Value.Decimal(r.double())
            case "bignum"  => Structure.Value.BigNum(BigDecimal(r.string()))
            case "null"    => r.skip(); Structure.Value.Null // skip consumes the nil body written by w.nil()
            case "seq" =>
                discard(r.arrayStart())
                val b = Chunk.newBuilder[Structure.Value]
                while r.hasNextElement() do b += read(r)
                r.arrayEnd()
                Structure.Value.Sequence(b.result())
            case "record" =>
                discard(r.objectStart())
                val b = Chunk.newBuilder[(String, Structure.Value)]
                while r.hasNextField() do
                    val n = r.field()
                    b += (n -> (read(r): Structure.Value))
                r.objectEnd()
                Structure.Value.Record(b.result())
            case "entries" => readMapEntries(r)
            case "variant" =>
                discard(r.objectStart())
                discard(r.field())
                val name = r.string()
                discard(r.hasNextField()) // consume ',' before the next field for text-format readers
                discard(r.field())
                val vv = read(r): Structure.Value
                r.objectEnd()
                Structure.Value.VariantCase(name, vv)
            case "bytes" =>
                Structure.Value.Bytes(r.bytes())
            case "instant" =>
                Structure.Value.Instant(r.instant())
            case "duration" =>
                Structure.Value.Duration(r.duration())
            case other =>
                throw TypeMismatchException(
                    Seq.empty,
                    "one of: str/int/bool/decimal/bignum/null/seq/record/entries/variant/bytes/instant/duration",
                    other
                )(using Frame.internal)
        r.objectEnd()
        MetadataValue(node)
    end read

    private def readMapEntries(r: Codec.Reader): Structure.Value.MapEntries =
        r match
            case ir: Codec.IntrospectingReader =>
                readMapEntriesFromStructure(readCapturedStructure(ir))
            case _ =>
                readMapEntriesStreaming(r)
    end readMapEntries

    private def readCapturedStructure(ir: Codec.IntrospectingReader): Structure.Value =
        ir.captureValue() match
            case sub: Codec.IntrospectingReader => sub.readStructure()
            case _ =>
                throw TypeMismatchException(Seq.empty, "introspecting metadata reader", "non-introspecting capture")(using Frame.internal)
    end readCapturedStructure

    private def readMapEntriesFromStructure(v: Structure.Value): Structure.Value.MapEntries =
        v match
            case Structure.Value.Record(fields) =>
                Structure.Value.MapEntries(Chunk.from(fields.map { (k, vv) =>
                    Structure.Value.Str(k) -> decodeTaggedMetadataValue(vv)
                }))
            case Structure.Value.Sequence(elements) =>
                Structure.Value.MapEntries(Chunk.from(elements.map {
                    case Structure.Value.Sequence(Chunk(k, vv)) =>
                        decodeTaggedMetadataValue(k) -> decodeTaggedMetadataValue(vv)
                    case other =>
                        throw TypeMismatchException(
                            Seq.empty,
                            "MapEntries pair [key, value]",
                            other.toString
                        )(using Frame.internal)
                }))
            case Structure.Value.MapEntries(entries) => Structure.Value.MapEntries(entries)
            case other =>
                throw TypeMismatchException(Seq.empty, "MapEntries map or pair array", other.toString)(using Frame.internal)
    end readMapEntriesFromStructure

    private def decodeTaggedMetadataValue(v: Structure.Value): Structure.Value =
        v match
            case Structure.Value.Record(Chunk((tag, inner))) =>
                tag match
                    case "str" | "int" | "bool" | "decimal" | "bignum" | "bytes" | "instant" | "duration" => inner
                    case "null"                                                                           => Structure.Value.Null
                    case "seq"                                                                            => decodeTaggedSequence(inner)
                    case "record"                                                                         => decodeTaggedRecord(inner)
                    case "entries" => readMapEntriesFromStructure(inner)
                    case "variant" => decodeTaggedVariant(inner)
                    case other =>
                        throw TypeMismatchException(
                            Seq.empty,
                            "one of: str/int/bool/decimal/bignum/null/seq/record/entries/variant/bytes/instant/duration",
                            other
                        )(using Frame.internal)
            case Structure.Value.Null => Structure.Value.Null
            case other =>
                throw TypeMismatchException(Seq.empty, "tagged metadata value", other.toString)(using Frame.internal)
    end decodeTaggedMetadataValue

    private def decodeTaggedSequence(v: Structure.Value): Structure.Value.Sequence =
        v match
            case Structure.Value.Sequence(elements) =>
                Structure.Value.Sequence(elements.map(decodeTaggedMetadataValue))
            case other =>
                throw TypeMismatchException(Seq.empty, "seq elements", other.toString)(using Frame.internal)
    end decodeTaggedSequence

    private def decodeTaggedRecord(v: Structure.Value): Structure.Value.Record =
        v match
            case Structure.Value.Record(fields) =>
                Structure.Value.Record(Chunk.from(fields.map((k, vv) => k -> decodeTaggedMetadataValue(vv))))
            case other =>
                throw TypeMismatchException(Seq.empty, "record fields", other.toString)(using Frame.internal)
    end decodeTaggedRecord

    private def decodeTaggedVariant(v: Structure.Value): Structure.Value.VariantCase =
        v match
            case Structure.Value.Record(fields) =>
                var name: Maybe[String]           = Maybe.empty
                var value: Maybe[Structure.Value] = Maybe.empty
                fields.foreach {
                    case ("name", Structure.Value.Str(n)) => name = Maybe(n)
                    case ("value", vv)                    => value = Maybe(decodeTaggedMetadataValue(vv))
                    case _                                => ()
                }
                (name, value) match
                    case (Maybe.Present(n), Maybe.Present(vv)) => Structure.Value.VariantCase(n, vv)
                    case _ =>
                        throw TypeMismatchException(Seq.empty, "variant name and value", v.toString)(using Frame.internal)
                end match
            case other =>
                throw TypeMismatchException(Seq.empty, "variant fields", other.toString)(using Frame.internal)
    end decodeTaggedVariant

    private def readMapEntriesStreaming(r: Codec.Reader): Structure.Value.MapEntries =
        try
            discard(r.objectStart())
            val b = Chunk.newBuilder[(Structure.Value, Structure.Value)]
            while r.hasNextField() do
                val k = Structure.Value.Str(r.field())
                b += (k -> (read(r): Structure.Value))
            r.objectEnd()
            Structure.Value.MapEntries(b.result())
        catch
            case _: TypeMismatchException =>
                discard(r.arrayStart())
                val b = Chunk.newBuilder[(Structure.Value, Structure.Value)]
                while r.hasNextElement() do
                    discard(r.arrayStart())
                    discard(r.hasNextElement())
                    val k = read(r): Structure.Value
                    discard(r.hasNextElement())
                    val vv = read(r): Structure.Value
                    r.arrayEnd()
                    b += (k -> vv)
                end while
                r.arrayEnd()
                Structure.Value.MapEntries(b.result())
    end readMapEntriesStreaming
end MetadataValue

/** Validated dotted-path metadata key, such as `trace.correlation_id`.
  *
  * Keys are non-empty and contain no empty segments: `""`, `.foo`, `foo.`, and `foo..bar` are all rejected. The constructor returns a
  * `Result`; there is no unchecked public construction.
  */
opaque type MetadataKey = String

object MetadataKey:
    /** Creates a validated metadata key, failing on an empty key or any empty dot-separated segment. */
    def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, MetadataKey] =
        if value.isEmpty || value.startsWith(".") || value.endsWith(".") || value.contains("..") then
            Result.fail(JournalInvalidIdentifierError("MetadataKey", value))
        else Result.succeed(value)

    extension (self: MetadataKey)
        /** The underlying dotted-path string. */
        def value: String = self

        /** The dot-separated segments of the key. */
        def segments: Chunk[String] = Chunk.from(self.split("\\."))
    end extension

    inline given CanEqual[MetadataKey, MetadataKey] = CanEqual.derived
end MetadataKey
