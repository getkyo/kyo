package kyo

/** The shape and identity vocabulary of a journaled event: `Event.Id`, `Event.Type`,
  * `Event.Metadata`, the stream-identity vocabulary (`Event.StreamId`/`Event.StreamName`/
  * `Event.StreamKey`/`Event.StreamOffset`/`Event.StreamVersion`/`Event.StreamSelector`), the
  * two lifecycle shapes (`Event.New`, `Event.Recorded`), and the per-member evidence
  * vocabulary consumed by `EventLog` (`Event.Definition`, `Event.IdPolicy`, `Event.Record`,
  * the typed attribute facade). `EventLog` stays the LOG API (`append`/`read`/`prepare`); this
  * namespace is the SHAPE of what an event IS, not what a log DOES with it.
  *
  * @see
  *   [[kyo.EventLog]] for the typed program facade that appends and reads events
  * @see
  *   [[kyo.Journal]] for the raw capability `EventLog` runs over
  */
sealed trait Event derives CanEqual:
    /** The event's producer-assigned identity. */
    def id: Event.Id

    /** The routing label consumers use to select a decoder. */
    def eventType: Event.Type

    /** Structural metadata stored beside the payload. */
    def metadata: Event.Metadata

    /** The raw, opaque payload bytes. `Span` equality via `==` is reference-based: compare
      * payload contents with `Span#is`, not by comparing events with `==`.
      */
    def payload: Span[Byte]
end Event

object Event:

    // ---- identity ----

    /** Identifier of a single event, assigned by the producer.
      *
      * Event identifiers travel with the event from [[Event.New]] to [[Event.Recorded]] unchanged; the journal does not generate or
      * deduplicate them. The constructor validates that the identifier is non-empty and returns a `Result`.
      *
      * @see
      *   [[kyo.Event.New]] which carries this identifier into an append
      */
    opaque type Id = String

    object Id:
        /** Creates a validated event identifier, failing on an empty value. */
        def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, Id] =
            if isValid(value) then Result.succeed(value)
            else Result.fail(JournalInvalidIdentifierError("EventId", value))

        /** Pure predicate reused by both this constructor and the `eventId"..."` compile-time
          * literal interpolator ([[kyo.internal.EventInterpolatorMacros]]), so the two never drift.
          */
        private[kyo] def isValid(value: String): Boolean = value.nonEmpty

        /** Constructs an id from a value already known valid (an internal invariant, never user input). */
        private[kyo] inline def fromUnchecked(value: String): Id = value

        extension (self: Id)
            /** The underlying string value. */
            def value: String = self

        inline given CanEqual[Id, Id] = CanEqual.derived
    end Id

    /** Type label of an event, used by consumers to select decoders.
      *
      * The routing label consumers use to select a decoder for the payload. The constructor validates that the label is non-empty and returns
      * a `Result`.
      *
      * @see
      *   [[kyo.Event.New]] which pairs this label with a raw payload
      */
    opaque type Type = String

    object Type:
        /** Creates a validated event type label, failing on an empty value. */
        def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, Type] =
            if isValid(value) then Result.succeed(value)
            else Result.fail(JournalInvalidIdentifierError("EventType", value))

        /** Pure predicate reused by both this constructor and the `eventType"..."` compile-time
          * literal interpolator ([[kyo.internal.EventInterpolatorMacros]]), so the two never drift.
          */
        private[kyo] def isValid(value: String): Boolean = value.nonEmpty

        /** Constructs a type label from a value already known valid (an internal invariant, never user input). */
        private[kyo] inline def fromUnchecked(value: String): Type = value

        extension (self: Type)
            /** The underlying string value. */
            def value: String = self

        inline given CanEqual[Type, Type] = CanEqual.derived
    end Type

    // ---- stream identity ----

    /** Identifier of an event stream within a [[Journal]].
      *
      * A stream is an append-only, zero-indexed sequence of events sharing one identity, typically one entity or one logical log. The
      * constructor validates that the identifier is non-empty and returns a `Result`; there is no unchecked public construction.
      *
      * @see
      *   [[kyo.Journal.append]] and [[kyo.Journal.read]] which address streams by this identifier
      */
    opaque type StreamId = String

    object StreamId:
        /** Creates a validated stream identifier, failing on an empty value. */
        def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, StreamId] =
            if isValid(value) then Result.succeed(value)
            else Result.fail(JournalInvalidIdentifierError("StreamId", value))

        /** Pure predicate reused by both this constructor and the `streamId"..."` compile-time
          * literal interpolator ([[kyo.internal.EventInterpolatorMacros]]), so the two never drift.
          */
        private[kyo] def isValid(value: String): Boolean = value.nonEmpty

        /** Constructs a stream id from a value already known valid (an internal invariant, never user input). */
        private[kyo] inline def fromUnchecked(value: String): StreamId = value

        extension (self: StreamId)
            /** The underlying string value. */
            def value: String = self

        inline given CanEqual[StreamId, StreamId] = CanEqual.derived
    end StreamId

    /** A validated, human-legible stream family name: the first component of every
      * `by`/`canonical`-derived stream id.
      */
    opaque type StreamName = String

    object StreamName:
        def apply(value: String)(using Frame): StreamName < Abort[EventLog.PreparationFailure] =
            if isValid(value) then value: StreamName
            else Abort.fail(EventLog.PreparationFailure("stream name must not be empty"))

        /** Pure predicate reused by both this constructor and the `streamName"..."` compile-time
          * literal interpolator ([[kyo.internal.EventInterpolatorMacros]]), so the two never drift.
          */
        private[kyo] def isValid(value: String): Boolean = value.nonEmpty

        /** Constructs a stream name from a value already known valid (an internal invariant, never user input). */
        private[kyo] inline def fromUnchecked(value: String): StreamName = value

        extension (self: StreamName)
            /** The underlying stream-name string. */
            def value: String = self
    end StreamName

    /** Derives one or more non-empty key components from a concrete event, combined with a
      * [[StreamName]] by [[StreamSelector.canonical]] into a collision-safe stream id.
      */
    final case class StreamKey[E] private (components: E => Chunk[String]) derives CanEqual
    object StreamKey:
        // The primary constructor is private so the case class's own synthesized apply (which
        // would otherwise be public and identical in arity save for this using clause) is private
        // too, leaving this apply as the sole public constructor path. Constructs via `new` (not
        // a bare StreamKey(components) call) so this method does not recurse into itself: both
        // the private synthesized apply and this apply remain visible from inside the companion.
        def apply[E](components: E => Chunk[String])(using Frame): StreamKey[E] = new StreamKey(components)
    end StreamKey

    /** Resolves the target stream for one concrete event. A real behavioral contract:
      * every concrete event routes to its resolved stream, never a stubbed marker.
      */
    sealed trait StreamSelector[E] derives CanEqual:
        def resolve(event: E)(using Frame): StreamId < Abort[EventLog.PreparationFailure]

    object StreamSelector:
        /** Every event routes to the same fixed stream, regardless of its content. */
        def constant[E](streamId: StreamId): StreamSelector[E] =
            ConstantStreamSelector(streamId)

        /** Every event routes to `name`/`f(event)`'s length-prefixed canonical stream id. */
        def by[E](name: StreamName)(f: E => Chunk[String]): StreamSelector[E] =
            KeyedStreamSelector(name, f)

        /** Every event routes to `name`/`key.components(event)`'s length-prefixed canonical
          * stream id.
          */
        def canonical[E](name: StreamName, key: StreamKey[E]): StreamSelector[E] =
            KeyedStreamSelector(name, key.components)

        /** A selector that aborts every resolution with `reason`, used only by the [[Event.Routes]]
          * supertype witness so an unrouted append fails loud instead of landing on a placeholder
          * stream.
          */
        private[kyo] def unroutable[E](reason: String): StreamSelector[E] =
            UnroutableStreamSelector(reason)
    end StreamSelector

    /** Zero-based position of an event within its stream.
      *
      * The first event of a stream is offset 0 and appends assign consecutive offsets. Valid values are in `[0, Long.MaxValue)`. Use
      * [[StreamVersion.after]] to convert an offset to a one-based count. The constructor validates the range and returns a `Result`.
      *
      * @see
      *   [[kyo.Event.StreamVersion]] for the one-based count view derived from an offset
      */
    opaque type StreamOffset = Long

    object StreamOffset:
        /** The offset of the first event in any stream. */
        val first: StreamOffset = 0L

        /** Creates a validated offset, failing outside `[0, Long.MaxValue)`. */
        def apply(value: Long)(using Frame): Result[JournalInvalidIdentifierError, StreamOffset] =
            if value < 0L || value == Long.MaxValue then
                Result.fail(JournalInvalidIdentifierError("StreamOffset", value.toString))
            else Result.succeed(value)

        /** Constructs an offset from a value already known to be valid (an internal invariant, never user input). */
        private[kyo] inline def fromUnchecked(value: Long): StreamOffset = value

        extension (self: StreamOffset)
            /** The underlying position value. */
            def value: Long = self

        inline given CanEqual[StreamOffset, StreamOffset] = CanEqual.derived
    end StreamOffset

    /** One-based count view of a stream: the number of events, or equivalently the position after the last event.
      *
      * `StreamVersion.initial` (zero) is the version of an absent or empty stream; `StreamVersion.after(offset)` is the version of a stream
      * whose last event sits at `offset`. The constructor validates non-negativity and returns a `Result`.
      *
      * @see
      *   [[kyo.Event.StreamOffset]] for the zero-based per-event position
      */
    opaque type StreamVersion = Long

    object StreamVersion:
        /** The version of an absent or empty stream. */
        val initial: StreamVersion = 0L

        /** Creates a validated version, failing on a negative value. */
        def apply(value: Long)(using Frame): Result[JournalInvalidIdentifierError, StreamVersion] =
            if value < 0L then Result.fail(JournalInvalidIdentifierError("StreamVersion", value.toString))
            else Result.succeed(value)

        /** The version of a stream whose last event sits at `offset`. */
        def after(offset: StreamOffset): StreamVersion =
            offset.value + 1L

        extension (self: StreamVersion)
            /** The underlying count value. */
            def value: Long = self

        inline given CanEqual[StreamVersion, StreamVersion] = CanEqual.derived
    end StreamVersion

    // ---- metadata (typed facade over the wire form) ----

    /** Structural metadata attached to a journaled event.
      *
      * Metadata is a map from validated dotted-path keys to structural values, stored beside the raw payload. It exists for infrastructure
      * concerns (correlation identifiers, tracing, tags) that consumers may need without decoding the payload. Values are
      * [[kyo.Event.Metadata.Value]] wrappers around [[kyo.Structure.Value]] trees with constructor-exact serialization fidelity.
      *
      * `get`/`put`/`contains` are a typed facade added over this unchanged wire form: each [[kyo.Event.AttributeKey]] names a stable
      * [[kyo.Event.Metadata.Key]] and carries the `Schema[A]` that encodes/decodes its attribute's value through the same
      * [[kyo.internal.StructureValueWriter]]/[[kyo.internal.StructureValueReader]] bridge the identity `Schema[Metadata.Value]` uses. The
      * `values` field and wire shape are unaffected; only the read/write surface is typed.
      *
      * @see
      *   [[kyo.Event.New]] and [[kyo.Event.Recorded]] which carry this metadata
      * @see
      *   [[kyo.Event.Metadata.Key]] for the key validation rules
      * @see
      *   [[kyo.Event.AttributeKey]] and [[kyo.Event.Attribute]] for the typed accessor keys
      */
    final case class Metadata(values: Map[Metadata.Key, Metadata.Value]) derives CanEqual:
        /** Typed read: decodes the stored [[Metadata.Value]] at `key.key` through `key.schema`. `Absent` only when the key is genuinely not
          * present; a present key whose stored shape does not match `key.schema` throws `TypeMismatchException` (matching
          * [[Metadata.Value.read]]'s own precedent), never a silent `Absent`.
          */
        def get[A](key: Event.AttributeKey[A])(using Frame): Maybe[A] =
            values.get(key.key) match
                case None     => Maybe.empty
                case Some(mv) => Maybe(key.schema.serializeRead(new kyo.internal.StructureValueReader(mv.value)))

        /** Typed write: encodes `attr.value` through `attr.key.schema` and stores it at `attr.key.key`, overwriting any existing entry
          * under that key.
          */
        def put[A](attr: Event.Attribute[A])(using Frame): Metadata =
            val writer = new kyo.internal.StructureValueWriter()
            attr.key.schema.serializeWrite(attr.value, writer)
            Metadata(values + (attr.key.key -> Metadata.Value(writer.getResult)))
        end put

        /** Whether `key` has a stored entry, independent of decodability. */
        def contains(key: Event.AttributeKey[?]): Boolean = values.contains(key.key)
    end Metadata

    object Metadata:
        /** Metadata with no entries. */
        val empty: Metadata = Metadata(Map.empty)

        /** Folds each attribute into a fresh map via [[Metadata.put]]; a later attribute in the varargs list wins over an earlier one
          * targeting the same key.
          */
        def of(attrs: Event.Attribute[?]*)(using Frame): Metadata =
            attrs.foldLeft(Metadata.empty)((acc, attr) => acc.put(attr))

        /** A metadata value that preserves constructor identity through serialization.
          *
          * Backed by [[kyo.Structure.Value]] directly with no allocation overhead. The accompanying [[Schema]] encodes each of the ten
          * constructors as a one-field record keyed by a fixed tag string (str, int, bool, decimal, bignum, null, seq, record, entries, variant),
          * so every constructor round-trips without loss through the self-describing binary codecs the file
          * backend uses (Ion Binary by default, MsgPack when selected explicitly). This covers the three
          * constructors the kyo-schema identity codec does not preserve: [[kyo.Structure.Value.VariantCase]],
          * [[kyo.Structure.Value.MapEntries]] with all-string keys, and [[kyo.Structure.Value.BigNum]]. The
          * text codecs do not read the tag-keyed open shape back for every constructor, so the round-trip
          * guarantee is scoped to the binary metadata path configured via [[kyo.EventLogCodecs.MetadataCodec]].
          *
          * Construct with [[Value.apply]] and project with [[Value.value]]; the opaque wrapper does not auto-convert.
          *
          * @see
          *   [[kyo.Event.Metadata]] which holds a map of these values
          * @see
          *   [[kyo.Event.Metadata.Key]] for the key validation rules
          * @see
          *   [[kyo.Structure.Value]] for the full constructor vocabulary
          */
        opaque type Value = Structure.Value

        object Value:
            /** Wraps a [[kyo.Structure.Value]] as a metadata value. */
            def apply(value: Structure.Value): Value = value

            extension (self: Value)
                /** The underlying structural value. */
                def value: Structure.Value = self

            /** Constructor-exact codec: each of the ten [[kyo.Structure.Value]] constructors encodes as a one-field record keyed by its tag
              * name and round-trips without loss through the binary metadata codec the file backend uses (Ion Binary by default). The tag-keyed
              * open shape is not read back by every text codec, so the guarantee is scoped to the binary metadata path.
              */
            given metadataValueSchema: Schema[Value] = Schema.init[Value](
                writeFn = (v, w) => write(w, v),
                readFn = r => read(r),
                structure = Structure.Type.Open(Tag[Structure.Value].asInstanceOf[Tag[Any]])
            )

            given CanEqual[Value, Value] = CanEqual.derived

            private[kyo] def write(w: Codec.Writer, v: Value): Unit =
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

            private[kyo] def read(r: Codec.Reader): Value =
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
                Value(node)
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
                        throw TypeMismatchException(Seq.empty, "introspecting metadata reader", "non-introspecting capture")(using
                            Frame.internal
                        )
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
                            case "seq"     => decodeTaggedSequence(inner)
                            case "record"  => decodeTaggedRecord(inner)
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
        end Value

        /** Validated dotted-path metadata key, such as `trace.correlation_id`.
          *
          * Keys are non-empty and contain no empty segments: `""`, `.foo`, `foo.`, and `foo..bar` are all rejected. The constructor returns a
          * `Result`; there is no unchecked public construction.
          */
        opaque type Key = String

        object Key:
            /** Creates a validated metadata key, failing on an empty key or any empty dot-separated segment. */
            def apply(value: String)(using Frame): Result[JournalInvalidIdentifierError, Key] =
                if isValid(value) then Result.succeed(value)
                else Result.fail(JournalInvalidIdentifierError("MetadataKey", value))

            /** Pure predicate reused by both this constructor and the `key"..."` compile-time literal
              * interpolator ([[kyo.internal.EventInterpolatorMacros]]), so the two never drift.
              */
            private[kyo] def isValid(value: String): Boolean =
                value.nonEmpty && !value.startsWith(".") && !value.endsWith(".") && !value.contains("..")

            /** Constructs a key from a value already known valid (an internal invariant, never user input). */
            private[kyo] inline def fromUnchecked(value: String): Key = value

            extension (self: Key)
                /** The underlying dotted-path string. */
                def value: String = self

                /** The dot-separated segments of the key. */
                def segments: Chunk[String] = Chunk.from(self.split("\\."))
            end extension

            inline given CanEqual[Key, Key] = CanEqual.derived
        end Key
    end Metadata

    // ---- lifecycle shapes ----

    /** An event as submitted to [[kyo.Journal.append]]: producer-assigned identity, type label, raw payload, and metadata.
      *
      * The journal treats the payload as opaque bytes; typed encoding and decoding live above this layer.
      *
      * @see
      *   [[kyo.Event.Recorded]] for the stored form returned by reads
      */
    final case class New(
        id: Event.Id,
        eventType: Event.Type,
        payload: Span[Byte],
        metadata: Event.Metadata
    ) extends Event derives CanEqual

    /** A stored event as returned by [[kyo.Journal.read]]: the pending event's data plus the stream identity and assigned offset.
      *
      * @see
      *   [[kyo.Event.New]] for the submitted form
      */
    final case class Recorded(
        streamId: Event.StreamId,
        offset: Event.StreamOffset,
        id: Event.Id,
        eventType: Event.Type,
        payload: Span[Byte],
        metadata: Event.Metadata
    ) extends Event derives CanEqual

    /** Structure-name-keyed registry of the per-member [[Event.Definition]]s backing an
      * [[kyo.EventLog]] built through [[kyo.EventLog.builder]]. One entry per routed leaf `E <: A`,
      * keyed by `Schema[E].structure.name` (the same string [[kyo.EventLog.deriveEventType]]
      * derives an [[Event.Type]] from), valued by its `Event.Definition[A, E]`. Carries a
      * `matcher` mapping a runtime value to its member's structure name, derived at
      * [[kyo.EventLog.Builder.build]] from compile-time member enumeration and installed by
      * `withMatcher`. Routing requires distinct member structure names: `duplicates` records every
      * structure-name key two members collided on, so `build` rejects the ambiguity loudly rather
      * than let one member silently shadow the other. Empty on the [[kyo.EventLog.init]] path;
      * populated by the builder on the [[kyo.EventLog.builder]] path.
      */
    final class Routes[A] private[kyo] (
        private[kyo] val entries: Map[String, Event.Definition[A, ?]],
        private[kyo] val matcher: A => String,
        private[kyo] val duplicates: Set[String]
    ):

        /** Whether no member route is registered, separating the [[kyo.EventLog.init]] path
          * (empty, hand-written static per-member givens) from the [[kyo.EventLog.builder]] path
          * (populated, runtime-value routing).
          */
        private[kyo] def isEmpty: Boolean = entries.isEmpty

        /** Registers one member's definition under `Schema[E].structure.name`, returning the
          * extended registry with the same `matcher`. When that key is already present the
          * structure name is recorded in `duplicates`, so `build` can reject the ambiguous
          * registration rather than let one member silently shadow the other.
          */
        private[kyo] def added[E <: A](definition: Event.Definition[A, E])(using schema: Schema[E]): Routes[A] =
            val key            = schema.structure.name
            val nextDuplicates = if entries.contains(key) then duplicates + key else duplicates
            new Routes[A](entries.updated(key, definition), matcher, nextDuplicates)
        end added

        /** Installs the value-to-structure-name `matcher` on the accumulated registry, called
          * once at `build` with the compile-time-derived matcher. Preserves `entries` and
          * `duplicates`.
          */
        private[kyo] def withMatcher(matcher: A => String): Routes[A] =
            new Routes[A](entries, matcher, duplicates)

        /** Total, non-throwing resolution backing `EventLog.witness` (the `routed` given) for a
          * statically-known type `E <: A`, keyed by `Schema[E].structure.name`. A registered leaf
          * key yields that member's real `Definition`; an absent key (the domain-supertype case,
          * where `E` is `A` itself and carries no per-member route) yields a single cached sentinel
          * `Definition` that only satisfies the given's signature. The sentinel is never consulted
          * for routing: builder-path appends resolve the real member by runtime value through
          * `EventLog.prepareDynamic`/`resolveDynamic`, not through this witness.
          */
        private[kyo] def witness[E <: A](using schema: Schema[E]): Event.Definition[A, E] =
            Maybe.fromOption(entries.get(schema.structure.name)) match
                case Present(definition) => definition.asInstanceOf[Event.Definition[A, E]]
                case Absent              => Routes.supertypeWitness.asInstanceOf[Event.Definition[A, E]]

        /** Runtime resolution for a value routed by its concrete member: every builder-path append
          * dispatches through here by the runtime value, as does the derived-`EventCodec` path.
          * Maps `value` to its member structure name through the baked `matcher`, then looks that
          * key up; aborts [[kyo.EventLog.PreparationFailure]] naming the unrouted member. Fails
          * loud, never a panic, when a value's member has no route.
          */
        private[kyo] def resolveDynamic(value: A)(using Frame): Event.Definition[A, ?] < Abort[EventLog.PreparationFailure] =
            val key = matcher(value)
            Maybe.fromOption(entries.get(key)) match
                case Present(definition) => definition
                case Absent =>
                    Abort.fail(EventLog.PreparationFailure(s"no route registered for event member $key"))
            end match
        end resolveDynamic
    end Routes

    object Routes:
        /** The empty registry installed on the [[kyo.EventLog.init]] path; its `matcher` is never
          * consulted there (runtime-value routing is a [[kyo.EventLog.builder]]-only path).
          */
        private[kyo] def empty[A]: Routes[A] = new Routes[A](Map.empty, (_: A) => "", Set.empty)

        /** The single cached sentinel `Definition` [[Routes.witness]] returns for the
          * domain-supertype case, built only from total constructors so the `routed` given never
          * throws at summon. A builder-built log routes every append by the runtime value through
          * `resolveDynamic` and never touches this definition. On the [[kyo.EventLog.init]] path,
          * `EventLog.prepare` recognizes this instance by reference identity through
          * [[Routes.isSupertypeWitness]] and aborts a typed [[kyo.EventLog.PreparationFailure]]
          * before reading any of its fields, so it is a pure identity marker. Its stream selector is
          * itself an aborting one ([[UnroutableStreamSelector]]) as a second layer: if that identity
          * guard were ever bypassed, the append still fails loud rather than land on a placeholder
          * stream.
          */
        private val supertypeWitness: Event.Definition[Any, Nothing] =
            Event.Definition[Any, Nothing](
                Event.Type.fromUnchecked("routed-supertype-witness"),
                Event.StreamSelector.unroutable(
                    "no route resolved for this append: a log from EventLog.init routes appends through a hand-written `given Event.Definition` per member, not through `import log.given`"
                ),
                Event.IdPolicy.generated[Nothing],
                EventLog.Metadata.empty[Nothing]
            )

        /** Whether `definition` is the routing-inert supertype witness, by reference identity. The
          * witness is a single cached instance produced only by [[Routes.witness]] for an unrouted
          * type; `EventLog.prepare` tests this to fail an append loud before any directive facet
          * (including a stream-id override) can route the event to a placeholder stream.
          */
        private[kyo] def isSupertypeWitness(definition: Event.Definition[?, ?]): Boolean =
            definition.asInstanceOf[AnyRef] eq supertypeWitness
    end Routes

    // ---- per-member evidence ----

    /** Per-member evidence: event type, stream, id policy, and metadata for concrete `E <: A`.
      * Carries no codec slot.
      */
    final case class Definition[A, E <: A](
        eventType: Event.Type,
        stream: Event.StreamSelector[E],
        eventId: Event.IdPolicy[E],
        metadata: EventLog.Metadata[E]
    )

    object Definition:
        /** Schema-derived member evidence. */
        def schema[A, E <: A](
            stream: Event.StreamSelector[E],
            eventId: Event.IdPolicy[E] = Event.IdPolicy.generated[E],
            metadata: EventLog.Metadata[E] = EventLog.Metadata.empty[E]
        )(using Schema[E], Frame): Definition[A, E] =
            // Derives eventType from Schema[E].structure.name (non-empty guaranteed by the
            // Event.Type smart constructor; a failing derivation is a compile-time absence).
            Definition(EventLog.deriveEventType[E], stream, eventId, metadata)
    end Definition

    /** Resolves the event id for one concrete event, given its resolved stream, type, and
      * metadata. A real behavioral contract. `generated` reads the shared monotonic
      * counter through the same internal Unsafe bridging pattern `prepareEnvelope` already
      * uses, so the row carries no `Sync`; `deterministic`/`callerSupplied` are pure over
      * their declared inputs.
      */
    sealed trait IdPolicy[E] derives CanEqual:
        def next(event: E, streamId: Event.StreamId, eventType: Event.Type, metadata: Event.Metadata)(using
            Frame
        ): Event.Id < Abort[EventLog.PreparationFailure]
    end IdPolicy

    object IdPolicy:
        /** Every event gets a fresh monotonic token from the shared in-process counter. */
        def generated[E]: IdPolicy[E] =
            GeneratedEventIdPolicy.asInstanceOf[IdPolicy[E]]

        /** Every event's id is `f(event, streamId, eventType, metadata)`, validated as an
          * [[Event.Id]]. Repeats for equal inputs and equal `f` output.
          */
        def deterministic[E](f: (E, Event.StreamId, Event.Type, Event.Metadata) => String)(using
            Frame
        ): IdPolicy[E] < Abort[EventLog.PreparationFailure] =
            DeterministicEventIdPolicy(f)

        /** Every event's id is `f(event)`, validated as an [[Event.Id]]. */
        def callerSupplied[E](f: E => String)(using Frame): IdPolicy[E] < Abort[EventLog.PreparationFailure] =
            CallerSuppliedEventIdPolicy(f)
    end IdPolicy

    /** A decoded record with a logical reference. */
    final case class Record[A](
        ref: JournalEntryRef,
        eventId: Event.Id,
        eventType: Event.Type,
        metadata: Event.Metadata,
        payload: A
    ) derives CanEqual

    // ---- typed attribute facade ----

    /** Typed, Schema[A]-checked facade over Metadata's unchanged wire form. Names a stable
      * wire key; the key's Schema[A] encodes/decodes its attribute's value through the same
      * StructureValueWriter/StructureValueReader bridge Metadata.get/put use. A
      * heterogeneous type-map keyed by erased Scala type is rejected: it has no stable,
      * cross-platform, cross-version wire identity, and two attributes of the same type would
      * collide; keying on this explicit validated Metadata.Key string is the wire-safe
      * alternative.
      */
    final case class AttributeKey[A](key: Event.Metadata.Key)(using val schema: Schema[A])

    /** One produced key-value pair for an event, consumed by Metadata.of/put. */
    final case class Attribute[A](key: AttributeKey[A], value: A)

    /** A per-member attribute-producer function: given the event, yield one Attribute or skip. */
    type AttributeBinding[E] = E => Maybe[Attribute[?]]

    extension [A](key: AttributeKey[A])
        /** Derives the attribute's value from the event. */
        def from[E](f: E => A): AttributeBinding[E] = event => Present(Attribute(key, f(event)))

        /** Produces a fixed attribute value regardless of the event. */
        def const[E](value: A): AttributeBinding[E] = _ => Present(Attribute(key, value))

        /** Produces the attribute only when `f` yields a present value. */
        def option[E](f: E => Maybe[A]): AttributeBinding[E] = event => f(event).map(v => Attribute(key, v))
    end extension

    /** Starter named-attribute vocabulary; users define their own AttributeKey vals alongside
      * these in their own vocabulary objects.
      */
    object Attributes:
        val CorrelationId: AttributeKey[String] =
            AttributeKey(Event.Metadata.Key("trace.correlation_id")(using Frame.internal).getOrThrow)
        val SourceSystem: AttributeKey[String] =
            AttributeKey(Event.Metadata.Key("source.system")(using Frame.internal).getOrThrow)
    end Attributes
end Event

/** Every event routes to the same fixed stream, regardless of its content. Built by
  * [[Event.StreamSelector.constant]].
  */
final private[kyo] case class ConstantStreamSelector[E](streamId: Event.StreamId) extends Event.StreamSelector[E]:
    def resolve(event: E)(using Frame): Event.StreamId < Abort[EventLog.PreparationFailure] = streamId

/** Aborts every resolution with `reason`, backing the [[Event.Routes]] supertype witness's stream
  * as a second layer of defense. `EventLog.prepare` already rejects the witness by reference
  * identity before this selector could run, so under the current append paths this abort is not
  * reached; it exists so that a value reaching stream resolution through the witness still fails
  * loud with a typed [[kyo.EventLog.PreparationFailure]] rather than route to a placeholder stream.
  */
final private[kyo] case class UnroutableStreamSelector[E](reason: String) extends Event.StreamSelector[E]:
    def resolve(event: E)(using Frame): Event.StreamId < Abort[EventLog.PreparationFailure] =
        Abort.fail(EventLog.PreparationFailure(reason))

/** Every event routes to `name`/`components(event)`'s length-prefixed canonical stream id.
  * Built by [[Event.StreamSelector.by]] and [[Event.StreamSelector.canonical]].
  */
final private[kyo] case class KeyedStreamSelector[E](name: Event.StreamName, components: E => Chunk[String])
    extends Event.StreamSelector[E]:
    def resolve(event: E)(using Frame): Event.StreamId < Abort[EventLog.PreparationFailure] =
        Abort.get(EventLogSupport.resolveKeyedStream(name, components(event)))
end KeyedStreamSelector

/** Every event gets a fresh monotonic token from the shared in-process counter, ignoring its
  * resolved stream, type, and metadata. Built by [[Event.IdPolicy.generated]].
  */
private[kyo] object GeneratedEventIdPolicy extends Event.IdPolicy[Any]:
    def next(event: Any, streamId: Event.StreamId, eventType: Event.Type, metadata: Event.Metadata)(using
        Frame
    ): Event.Id < Abort[EventLog.PreparationFailure] =
        // Unsafe: the counter increment is a synchronous computation with no real suspension
        // point; evaluated inline so this row carries no Sync, matching
        // deterministic/callerSupplied's pure rows.
        given AllowUnsafe = AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow[Event.Id](EventLogSupport.freshEventId())
    end next
end GeneratedEventIdPolicy

/** Every event's id is `f(event, streamId, eventType, metadata)`, validated as an [[Event.Id]].
  * Repeats for equal inputs and equal `f` output. Built by
  * [[Event.IdPolicy.deterministic]].
  */
final private[kyo] case class DeterministicEventIdPolicy[E](f: (E, Event.StreamId, Event.Type, Event.Metadata) => String)
    extends Event.IdPolicy[E]:
    def next(event: E, streamId: Event.StreamId, eventType: Event.Type, metadata: Event.Metadata)(using
        Frame
    ): Event.Id < Abort[EventLog.PreparationFailure] =
        Abort.get(Event.Id(f(event, streamId, eventType, metadata)).mapFailure(e =>
            EventLog.PreparationFailure(s"deterministic event id policy produced an invalid id: ${e.getMessage()}")
        ))
end DeterministicEventIdPolicy

/** Every event's id is `f(event)`, validated as an [[Event.Id]]. Built by
  * [[Event.IdPolicy.callerSupplied]].
  */
final private[kyo] case class CallerSuppliedEventIdPolicy[E](f: E => String) extends Event.IdPolicy[E]:
    def next(event: E, streamId: Event.StreamId, eventType: Event.Type, metadata: Event.Metadata)(using
        Frame
    ): Event.Id < Abort[EventLog.PreparationFailure] =
        Abort.get(Event.Id(f(event)).mapFailure(e =>
            EventLog.PreparationFailure(s"caller-supplied event id is invalid: ${e.getMessage()}")
        ))
end CallerSuppliedEventIdPolicy

// ---- operation-outcome vocabulary (Journal-operation shapes, distinct from event identity) ----

/** Optimistic concurrency expectation for an append.
  *
  * The check is atomic with the append: `Any` skips it, `NoStream` requires the stream to be absent, and `Exact(offset)` requires the
  * live last offset to equal `offset`. A mismatch fails the append with [[JournalConflictError]] carrying the observed [[StreamInfo]],
  * leaving the stream unchanged.
  *
  * @see
  *   [[kyo.Journal.append]] which takes this expectation
  */
enum ExpectedOffset derives CanEqual:
    case Any
    case NoStream
    case Exact(offset: Event.StreamOffset)
end ExpectedOffset

/** Observed state of a stream: absent, or existing with a stream version and last offset.
  *
  * `StreamInfo.Existing(version, lastOffset)` carries the one-based event count as a typed [[Event.StreamVersion]] and the zero-based offset
  * of the last event. For a contiguous zero-based stream `version.value == lastOffset.value + 1` always holds. Returned by
  * [[kyo.Journal.streamInfo]] and carried inside [[JournalConflictError]] so a failed optimistic append reports what it actually observed.
  */
enum StreamInfo derives CanEqual:
    case Absent
    case Existing(version: Event.StreamVersion, lastOffset: Event.StreamOffset)

    /** Whether the stream has at least one event. */
    def exists: Boolean =
        this match
            case StreamInfo.Absent         => false
            case StreamInfo.Existing(_, _) => true
end StreamInfo

/** Outcome of a successful append: the offset range assigned to the batch and the post-append stream state.
  *
  * @see
  *   [[kyo.Journal.append]] which returns this
  */
final case class AppendResult(
    streamId: Event.StreamId,
    firstOffset: Event.StreamOffset,
    lastOffset: Event.StreamOffset,
    streamInfo: StreamInfo
) derives CanEqual
