package kyo.internal.postgres.types

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present

/** Registry mapping (OID, format) to wire encoders and decoders.
  *
  * The builtin registry covers all standard SQL scalar types. Use [[register]] to add custom type codecs; the returned registry chains
  * user-registered entries before the builtin entries so custom codecs take precedence.
  *
  * Usage:
  * {{{
  * val myEncoder: PostgresEncoder[MyType] = ...
  * val myDecoder: PostgresDecoder[MyType] = ...
  * val registry = EncodingRegistry.builtin.register(myOid, myEncoder, myDecoder)
  * // pass `registry` via SqlClientConfig.encodingRegistry
  * }}}
  */
trait EncodingRegistry:
    /** Returns the encoder for OID `oid` in the given `format`, if one is registered. */
    def encoderByOid(oid: Int, format: Format): Maybe[PostgresEncoder[?]]

    /** Returns the decoder for OID `oid` in the given `format`, if one is registered. */
    def decoderByOid(oid: Int, format: Format): Maybe[PostgresDecoder[?]]

    /** Registers a custom encoder and decoder for the given OID and returns a new registry that checks user-registered entries first.
      *
      * The encoder's [[PostgresEncoder.format]] determines the format key under which the encoder is stored. The decoder is registered for
      * both Text and Binary format to ensure it is found regardless of the format the server sends.
      *
      * Repeated calls to [[register]] chain registrations; a later call wins over an earlier call for the same OID.
      *
      * @param oid
      *   PostgreSQL OID for the custom type (e.g. `3807` for `jsonb[]`, a user-defined type OID from `pg_type.oid`)
      * @param encoder
      *   encoder that writes values of type `T` into the Bind wire format
      * @param decoder
      *   decoder that reads the wire bytes back into `T`
      * @return
      *   a new [[EncodingRegistry]] with the custom codec registered; `this` is unchanged
      */
    def register[T](oid: Int, encoder: PostgresEncoder[T], decoder: PostgresDecoder[T]): EncodingRegistry

    /** Registers a custom encoder and decoder under a named type, in addition to the OID key.
      *
      * The `typeName` is informational and aids debugging; lookup is always by OID.
      *
      * @param typeName
      *   human-readable type name (e.g. `"geometry"`, `"hstore"`)
      * @param oid
      *   PostgreSQL OID for the custom type
      * @param encoder
      *   encoder for values of the custom type
      * @param decoder
      *   decoder for values of the custom type
      * @return
      *   a new [[EncodingRegistry]] with the custom codec registered; `this` is unchanged
      */
    def registerCustom(typeName: String, oid: Int, encoder: PostgresEncoder[?], decoder: PostgresDecoder[?]): EncodingRegistry
end EncodingRegistry

object EncodingRegistry:

    /** Reference equality for [[EncodingRegistry]], registries are compared by identity, not by content. */
    given CanEqual[EncodingRegistry, EncodingRegistry] = CanEqual.canEqualAny

    /** An empty registry with no encoders or decoders registered. Useful as a base for building a custom-only registry in tests. */
    val empty: EncodingRegistry = LayeredEncodingRegistry(Map.empty, Map.empty, None)

    /** Builtin registry covering all standard scalar types (Bool, Int2, Int4, Int8, Float4, Float8, Numeric, Text, Bytea, Timestamptz,
      * Date, Timestamp, Time).
      *
      * Lookup order:
      *   1. Find encoder/decoder registered for (oid, format).
      *   2. If not found for Binary, fall back to Text for the same OID.
      *   3. If still not found, return Absent.
      *
      * Use [[register]] to add custom codecs on top of this registry.
      */
    val builtin: EncodingRegistry =

        // Encoders keyed by (oid, format.code)
        val encoders: Map[(Int, Short), PostgresEncoder[?]] = Map(
            (PostgresEncoder.OID_BOOL, Format.Text.code)          -> PostgresEncoder.boolText,
            (PostgresEncoder.OID_BOOL, Format.Binary.code)        -> PostgresEncoder.boolBinary,
            (PostgresEncoder.OID_INT2, Format.Text.code)          -> PostgresEncoder.int2Text,
            (PostgresEncoder.OID_INT2, Format.Binary.code)        -> PostgresEncoder.int2Binary,
            (PostgresEncoder.OID_INT4, Format.Text.code)          -> PostgresEncoder.int4Text,
            (PostgresEncoder.OID_INT4, Format.Binary.code)        -> PostgresEncoder.int4Binary,
            (PostgresEncoder.OID_INT8, Format.Text.code)          -> PostgresEncoder.int8Text,
            (PostgresEncoder.OID_INT8, Format.Binary.code)        -> PostgresEncoder.int8Binary,
            (PostgresEncoder.OID_FLOAT4, Format.Text.code)        -> PostgresEncoder.float4Text,
            (PostgresEncoder.OID_FLOAT4, Format.Binary.code)      -> PostgresEncoder.float4Binary,
            (PostgresEncoder.OID_FLOAT8, Format.Text.code)        -> PostgresEncoder.float8Text,
            (PostgresEncoder.OID_FLOAT8, Format.Binary.code)      -> PostgresEncoder.float8Binary,
            (PostgresEncoder.OID_NUMERIC, Format.Text.code)       -> PostgresEncoder.numericText,
            (PostgresEncoder.OID_NUMERIC, Format.Binary.code)     -> PostgresEncoder.numericBinary,
            (PostgresEncoder.OID_TEXT, Format.Text.code)          -> PostgresEncoder.textText,
            (PostgresEncoder.OID_BYTEA, Format.Binary.code)       -> PostgresEncoder.byteaBinary,
            (PostgresEncoder.OID_BYTEA, Format.Text.code)         -> PostgresEncoder.byteaText,
            (PostgresEncoder.OID_TIMESTAMPTZ, Format.Binary.code) -> PostgresEncoder.timestamptzBinary,
            (PostgresEncoder.OID_TIMESTAMPTZ, Format.Text.code)   -> PostgresEncoder.timestamptzText,
            (PostgresEncoder.OID_DATE, Format.Binary.code)        -> PostgresEncoder.dateBinary,
            (PostgresEncoder.OID_DATE, Format.Text.code)          -> PostgresEncoder.dateText,
            (PostgresEncoder.OID_TIMESTAMP, Format.Binary.code)   -> PostgresEncoder.timestampBinary,
            (PostgresEncoder.OID_TIMESTAMP, Format.Text.code)     -> PostgresEncoder.timestampText,
            (PostgresEncoder.OID_TIME, Format.Binary.code)        -> PostgresEncoder.timeBinary,
            (PostgresEncoder.OID_TIME, Format.Text.code)          -> PostgresEncoder.timeText,
            (PostgresEncoder.OID_TIMETZ, Format.Binary.code)      -> PostgresEncoder.timetzBinary,
            (PostgresEncoder.OID_UUID, Format.Binary.code)        -> PostgresEncoder.uuidBinary,
            (PostgresEncoder.OID_INET, Format.Binary.code)        -> PostgresEncoder.inetBinary,
            (PostgresEncoder.OID_CIDR, Format.Binary.code)        -> PostgresEncoder.cidrBinary,
            (PostgresEncoder.OID_INT4_ARRAY, Format.Binary.code)  -> PostgresEncoder.int4ArrayBinary,
            (PostgresEncoder.OID_TEXT_ARRAY, Format.Binary.code)  -> PostgresEncoder.textArrayBinary,
            (PostgresEncoder.OID_JSONB_ARRAY, Format.Binary.code) -> PostgresEncoder.jsonbArrayBinary,
            (PostgresEncoder.OID_JSON, Format.Text.code)          -> PostgresEncoder.jsonText,
            (PostgresEncoder.OID_JSONB, Format.Binary.code)       -> PostgresEncoder.jsonbBinary
        )

        // Decoders keyed by (oid, format.code); decoders that accept multiple OIDs are registered for each.
        val allDecoders: Seq[PostgresDecoder[?]] = Seq(
            PostgresDecoder.bool,
            PostgresDecoder.int2,
            PostgresDecoder.int4,
            PostgresDecoder.int8,
            PostgresDecoder.float4,
            PostgresDecoder.float8,
            PostgresDecoder.numeric,
            PostgresDecoder.textDecoder,
            PostgresDecoder.bytea,
            PostgresDecoder.timestamptz,
            PostgresDecoder.date,
            PostgresDecoder.timestamp,
            PostgresDecoder.time,
            PostgresDecoder.timetz,
            PostgresDecoder.uuid,
            PostgresDecoder.inet,
            PostgresDecoder.cidr,
            PostgresDecoder.int4Array,
            PostgresDecoder.textArray,
            PostgresDecoder.jsonbArray,
            PostgresDecoder.jsonDecoder
        )
        val formats = Seq(Format.Text, Format.Binary)
        val decoderEntries =
            for
                dec    <- allDecoders
                oid    <- dec.oids
                format <- formats
            yield (oid, format.code) -> dec
        val decoders: Map[(Int, Short), PostgresDecoder[?]] = decoderEntries.toMap

        BuiltinEncodingRegistry(encoders, decoders)
    end builtin

    /** Builtin [[EncodingRegistry]] implementation backed by immutable maps.
      *
      * Lookup order for encoders: exact (oid, format) match. Lookup order for decoders: exact (oid, format) match, then fallback to (oid,
      * Text) if the exact key is absent.
      *
      * Implements [[register]] and [[registerCustom]] by returning a [[LayeredEncodingRegistry]] that searches user-supplied entries first,
      * then delegates to `this`.
      */
    final private class BuiltinEncodingRegistry(
        private val encoders: Map[(Int, Short), PostgresEncoder[?]],
        private val decoders: Map[(Int, Short), PostgresDecoder[?]]
    ) extends EncodingRegistry:

        def encoderByOid(oid: Int, format: Format): Maybe[PostgresEncoder[?]] =
            encoders.get((oid, format.code)) match
                case Some(enc) => Present(enc)
                case None      =>
                    // Fallback to the other format (Text↔Binary) only when explicitly registered.
                    val fallbackFormat = if format == Format.Binary then Format.Text else Format.Binary
                    encoders.get((oid, fallbackFormat.code)) match
                        case Some(enc) => Present(enc)
                        case None      => Absent

        def decoderByOid(oid: Int, format: Format): Maybe[PostgresDecoder[?]] =
            decoders.get((oid, format.code)) match
                case Some(dec) => Present(dec)
                case None      =>
                    // Fallback: try Text format for the same OID (e.g. server sent text but we asked for binary).
                    decoders.get((oid, Format.Text.code)) match
                        case Some(dec) => Present(dec)
                        case None      => Absent

        def register[T](oid: Int, encoder: PostgresEncoder[T], decoder: PostgresDecoder[T]): EncodingRegistry =
            LayeredEncodingRegistry(
                Map((oid, encoder.format.code) -> encoder),
                Map((oid, Format.Text.code)    -> decoder, (oid, Format.Binary.code) -> decoder),
                Some(this)
            )

        def registerCustom(typeName: String, oid: Int, encoder: PostgresEncoder[?], decoder: PostgresDecoder[?]): EncodingRegistry =
            LayeredEncodingRegistry(
                Map((oid, encoder.format.code) -> encoder),
                Map((oid, Format.Text.code)    -> decoder, (oid, Format.Binary.code) -> decoder),
                Some(this),
                typeName = typeName
            )
    end BuiltinEncodingRegistry

    /** An [[EncodingRegistry]] that searches user-registered entries first, then delegates to an optional fallback registry.
      *
      * Produced by [[BuiltinEncodingRegistry.register]] / [[BuiltinEncodingRegistry.registerCustom]]; supports chaining via further
      * [[register]] calls, accumulating entries in the layer's own maps.
      */
    final class LayeredEncodingRegistry(
        private val userEncoders: Map[(Int, Short), PostgresEncoder[?]],
        private val userDecoders: Map[(Int, Short), PostgresDecoder[?]],
        private val fallback: Option[EncodingRegistry],
        private val typeName: String = ""
    ) extends EncodingRegistry:

        def encoderByOid(oid: Int, format: Format): Maybe[PostgresEncoder[?]] =
            userEncoders.get((oid, format.code)) match
                case Some(enc) => Present(enc)
                case None =>
                    fallback match
                        case Some(fb) => fb.encoderByOid(oid, format)
                        case None     => Absent

        def decoderByOid(oid: Int, format: Format): Maybe[PostgresDecoder[?]] =
            userDecoders.get((oid, format.code)) match
                case Some(dec) => Present(dec)
                case None =>
                    fallback match
                        case Some(fb) => fb.decoderByOid(oid, format)
                        case None     => Absent

        def register[T](oid: Int, encoder: PostgresEncoder[T], decoder: PostgresDecoder[T]): EncodingRegistry =
            LayeredEncodingRegistry(
                userEncoders + ((oid, encoder.format.code) -> encoder),
                userDecoders + ((oid, Format.Text.code)    -> decoder) + ((oid, Format.Binary.code) -> decoder),
                fallback
            )

        def registerCustom(typeName: String, oid: Int, encoder: PostgresEncoder[?], decoder: PostgresDecoder[?]): EncodingRegistry =
            LayeredEncodingRegistry(
                userEncoders + ((oid, encoder.format.code) -> encoder),
                userDecoders + ((oid, Format.Text.code)    -> decoder) + ((oid, Format.Binary.code) -> decoder),
                fallback,
                typeName = typeName
            )
    end LayeredEncodingRegistry

end EncodingRegistry
