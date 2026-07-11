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
  * so every constructor round-trips without loss through the self-describing MsgPack codec, which is the codec the file backend uses. This
  * covers the three constructors the kyo-schema identity codec does not preserve: [[kyo.Structure.Value.VariantCase]],
  * [[kyo.Structure.Value.MapEntries]] with all-string keys, and [[kyo.Structure.Value.BigNum]]. The text codecs do not read the tag-keyed
  * open shape back for every constructor, so the round-trip guarantee is scoped to the binary MsgPack path.
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
      * name and round-trips without loss through the self-describing MsgPack codec the file backend uses. The tag-keyed open shape is
      * not read back by every text codec, so the guarantee is scoped to the binary MsgPack path.
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
                w.arrayStart(entries.size)
                entries.foreach((k, vv) =>
                    w.arrayStart(2); write(w, k); write(w, vv); w.arrayEnd()
                )
                w.arrayEnd()
            case Structure.Value.VariantCase(name, vv) =>
                w.field("variant", 0)
                w.mapStart(2)
                w.field("name", 0); w.string(name)
                w.field("value", 0); write(w, vv)
                w.mapEnd()
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
            case "entries" =>
                discard(r.arrayStart())
                val b = Chunk.newBuilder[(Structure.Value, Structure.Value)]
                while r.hasNextElement() do
                    discard(r.arrayStart())
                    val k = read(r): Structure.Value
                    discard(r.hasNextElement()) // consume ',' before the value element for text-format readers
                    val vv = read(r): Structure.Value
                    r.arrayEnd()
                    b += (k -> vv)
                end while
                r.arrayEnd()
                Structure.Value.MapEntries(b.result())
            case "variant" =>
                discard(r.objectStart())
                discard(r.field())
                val name = r.string()
                discard(r.hasNextField()) // consume ',' before the next field for text-format readers
                discard(r.field())
                val vv = read(r): Structure.Value
                r.objectEnd()
                Structure.Value.VariantCase(name, vv)
            case other =>
                throw TypeMismatchException(
                    Seq.empty,
                    "one of: str/int/bool/decimal/bignum/null/seq/record/entries/variant",
                    other
                )(using Frame.internal)
        r.objectEnd()
        MetadataValue(node)
    end read
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
