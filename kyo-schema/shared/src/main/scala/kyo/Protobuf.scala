package kyo

import kyo.DecodeException
import kyo.Frame
import kyo.Result
import kyo.Schema
import kyo.Span

final class Protobuf extends Codec:
    def newWriter(): Codec.Writer = new kyo.internal.ProtobufWriter()
    def newReader(input: Span[Byte])(using Frame): Codec.Reader =
        new kyo.internal.ProtobufReader(input.toArray)
end Protobuf

/** Entry point for Protocol Buffers binary serialization, schema generation, and gRPC message framing.
  *
  * Encoding produces proto3 wire-format bytes. Field numbers are derived from stable MurmurHash3 hashes of field names, enabling schema
  * evolution without breaking existing encoded data.
  *
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  * @see
  *   [[kyo.Json]] for JSON serialization
  */
object Protobuf:
    given Protobuf = Protobuf()

    private val GrpcHeaderSize       = 5
    private val GrpcUncompressedFlag = 0.toByte

    /** Encodes a value of type A to Protocol Buffers binary bytes.
      *
      * @param value
      *   the value to encode
      * @return
      *   the proto3 wire-format bytes
      */
    inline def encode[A](value: A)(using schema: Schema[A], frame: Frame): Span[Byte] =
        val w = summon[Protobuf].newWriter()
        schema.writeTo(value, w)
        w.result()
    end encode

    /** Decodes Protocol Buffers binary bytes into a value of type A.
      *
      * @param input
      *   the proto3 wire-format bytes to decode
      * @return
      *   the decoded value, or a DecodeException if the input is malformed or does not match the schema
      */
    def decode[A](
        input: Span[Byte],
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize
    )(using protobuf: Protobuf, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        val reader = protobuf.newReader(input)
        reader.resetLimits(maxDepth, maxCollectionSize)
        Result.catching[DecodeException](schema.readFrom(reader))
    end decode

    /** Encodes a value of type A as a single uncompressed gRPC message.
      *
      * The result is a gRPC Length-Prefixed-Message: a one-byte compression flag followed by a four-byte big-endian message length and the
      * Protobuf payload. This helper covers the wire shape used by unary calls and each element of streaming calls while leaving service
      * dispatch and HTTP/2 transport concerns to a higher-level gRPC module.
      *
      * @param value
      *   the value to encode
      * @return
      *   a gRPC-framed Protobuf message
      */
    inline def encodeGrpc[A](value: A)(using schema: Schema[A], frame: Frame): Span[Byte] =
        encodeGrpcPayload(encode(value))
    end encodeGrpc

    /** Decodes a single uncompressed gRPC message into a value of type A.
      *
      * The input must contain exactly one gRPC Length-Prefixed-Message. Compressed messages are rejected because compression negotiation is
      * part of the surrounding gRPC transport metadata, not the Protobuf payload itself. Use `maxMessageSize` to enforce a per-message
      * receive limit before the payload is decoded.
      *
      * @param input
      *   the gRPC-framed Protobuf message
      * @param maxMessageSize
      *   the maximum allowed decoded payload size in bytes
      * @param maxDepth
      *   the maximum nesting depth accepted by the Protobuf decoder
      * @param maxCollectionSize
      *   the maximum collection size accepted by the Protobuf decoder
      * @return
      *   the decoded value, or a DecodeException if the frame or payload is invalid
      */
    def decodeGrpc[A](
        input: Span[Byte],
        maxMessageSize: Int = Int.MaxValue,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize
    )(using protobuf: Protobuf, schema: Schema[A], frame: Frame): Result[DecodeException, A] =
        decodeGrpcPayload(input, maxMessageSize).flatMap(payload => decode[A](payload, maxDepth, maxCollectionSize))
    end decodeGrpc

    /** Encodes raw Protobuf payload bytes as a single uncompressed gRPC message.
      *
      * The payload is copied behind the standard five-byte gRPC message header. The compression flag is always `0`, matching an
      * uncompressed message. Higher-level clients and servers can use this when they already own Protobuf bytes and only need correct gRPC
      * message framing.
      *
      * @param payload
      *   raw Protobuf payload bytes
      * @return
      *   a gRPC Length-Prefixed-Message
      */
    def encodeGrpcPayload(payload: Span[Byte]): Span[Byte] =
        val payloadSize = payload.size
        val out         = new Array[Byte](GrpcHeaderSize + payloadSize)
        writeGrpcPayload(payload, out, 0)
        Span.fromUnsafe(out)
    end encodeGrpcPayload

    /** Encodes raw Protobuf payload bytes as consecutive uncompressed gRPC messages.
      *
      * gRPC streaming bodies are represented as a sequence of Length-Prefixed-Message values. This helper frames each payload in order and
      * concatenates the resulting bytes so a transport layer can stream or buffer the result without reimplementing the gRPC frame header.
      *
      * @param payloads
      *   raw Protobuf payload bytes, one entry per gRPC message
      * @return
      *   concatenated gRPC Length-Prefixed-Message bytes
      */
    def encodeGrpcPayloads(payloads: IterableOnce[Span[Byte]]): Span[Byte] =
        val chunk = Chunk.from(payloads)
        var total = 0L
        chunk.foreach(payload => total += GrpcHeaderSize + payload.size)
        if total > Int.MaxValue then
            throw new IllegalArgumentException(s"gRPC payload stream is too large to fit in a single Span[Byte]: $total bytes")

        val out    = new Array[Byte](total.toInt)
        var offset = 0
        chunk.foreach { payload =>
            writeGrpcPayload(payload, out, offset)
            offset += GrpcHeaderSize + payload.size
        }
        Span.fromUnsafe(out)
    end encodeGrpcPayloads

    /** Decodes a single uncompressed gRPC message to raw Protobuf payload bytes.
      *
      * The input must contain exactly one Length-Prefixed-Message. Use `decodeGrpcPayloads` when parsing a streaming body with multiple
      * frames. The returned payload can be passed to `Protobuf.decode` or to a generated service binding.
      *
      * @param input
      *   a gRPC Length-Prefixed-Message
      * @param maxMessageSize
      *   the maximum allowed payload size in bytes
      * @return
      *   the raw Protobuf payload, or a DecodeException if the frame is invalid
      */
    def decodeGrpcPayload(
        input: Span[Byte],
        maxMessageSize: Int = Int.MaxValue
    )(using protobuf: Protobuf, frame: Frame): Result[DecodeException, Span[Byte]] =
        if maxMessageSize < 0 then Result.fail(LimitExceededException("gRPC message size", 0, maxMessageSize))
        else
            decodeGrpcPayloadAt(input, 0, maxMessageSize).flatMap { case (payload, nextOffset) =>
                if nextOffset == input.size then Result.succeed(payload)
                else
                    Result.fail(
                        ParseException(protobuf, s"${input.size - nextOffset} trailing bytes", "single gRPC message", position = nextOffset)
                    )
            }
    end decodeGrpcPayload

    /** Decodes consecutive uncompressed gRPC messages to raw Protobuf payload bytes.
      *
      * Empty input decodes to an empty Chunk. Each frame is validated independently: compressed frames, truncated headers, truncated
      * payloads, and payloads larger than `maxMessageSize` return a DecodeException before any payload is decoded as Protobuf.
      *
      * @param input
      *   concatenated gRPC Length-Prefixed-Message bytes
      * @param maxMessageSize
      *   the maximum allowed payload size for each message
      * @return
      *   one raw Protobuf payload per gRPC frame
      */
    def decodeGrpcPayloads(
        input: Span[Byte],
        maxMessageSize: Int = Int.MaxValue
    )(using protobuf: Protobuf, frame: Frame): Result[DecodeException, Chunk[Span[Byte]]] =
        val builder = Chunk.newBuilder[Span[Byte]]

        @annotation.tailrec
        def loop(offset: Int): Result[DecodeException, Chunk[Span[Byte]]] =
            if offset == input.size then Result.succeed(builder.result())
            else
                decodeGrpcPayloadAt(input, offset, maxMessageSize) match
                    case Result.Success((payload, nextOffset)) =>
                        discard(builder.addOne(payload))
                        loop(nextOffset)
                    case Result.Failure(ex) =>
                        Result.fail(ex)
                    case Result.Panic(ex) =>
                        Result.panic(ex)
                end match
            end if
        end loop

        if maxMessageSize < 0 then Result.fail(LimitExceededException("gRPC message size", 0, maxMessageSize))
        else loop(0)
    end decodeGrpcPayloads

    private def writeGrpcPayload(payload: Span[Byte], out: Array[Byte], offset: Int): Unit =
        val payloadSize = payload.size
        out(offset) = GrpcUncompressedFlag
        out(offset + 1) = ((payloadSize >>> 24) & 0xff).toByte
        out(offset + 2) = ((payloadSize >>> 16) & 0xff).toByte
        out(offset + 3) = ((payloadSize >>> 8) & 0xff).toByte
        out(offset + 4) = (payloadSize & 0xff).toByte
        java.lang.System.arraycopy(payload.toArrayUnsafe, 0, out, offset + GrpcHeaderSize, payloadSize)
    end writeGrpcPayload

    private def decodeGrpcPayloadAt(
        input: Span[Byte],
        offset: Int,
        maxMessageSize: Int
    )(using protobuf: Protobuf, frame: Frame): Result[DecodeException, (Span[Byte], Int)] =
        val remaining = input.size - offset
        if remaining < GrpcHeaderSize then
            Result.fail(TruncatedInputException(protobuf, s"gRPC message header requires $GrpcHeaderSize bytes but only $remaining remain"))
        else
            val compressionFlag = input(offset)
            if compressionFlag != GrpcUncompressedFlag then
                Result.fail(ParseException(protobuf, compressionFlag.toString, "uncompressed gRPC message", position = offset))
            else
                val payloadSize =
                    ((input(offset + 1).toLong & 0xffL) << 24) |
                        ((input(offset + 2).toLong & 0xffL) << 16) |
                        ((input(offset + 3).toLong & 0xffL) << 8) |
                        (input(offset + 4).toLong & 0xffL)
                if payloadSize > maxMessageSize then
                    val actual = math.min(payloadSize, Int.MaxValue.toLong).toInt
                    Result.fail(LimitExceededException("gRPC message size", actual, maxMessageSize))
                else
                    val payloadOffset = offset + GrpcHeaderSize
                    val nextOffset    = payloadOffset.toLong + payloadSize
                    if nextOffset > input.size then
                        Result.fail(
                            TruncatedInputException(
                                protobuf,
                                s"gRPC message declared $payloadSize bytes but only ${input.size - payloadOffset} remain"
                            )
                        )
                    else
                        val next = nextOffset.toInt
                        Result.succeed((input.slice(payloadOffset, next), next))
                    end if
                end if
            end if
        end if
    end decodeGrpcPayloadAt

    /** Generates a `.proto` file definition string for type A at compile time.
      *
      * The returned string uses proto3 syntax and can be written directly to a `.proto` file. Nested case classes are emitted as separate
      * message definitions.
      *
      * @tparam A
      *   the type to describe
      * @return
      *   a proto3 schema string ready for use as a `.proto` file
      */
    inline def protoSchema[A]: String = ProtoSchema.from[A]

    /** Generates Protocol Buffers schema (.proto file content) from Scala types.
      *
      * Supports:
      *   - Case classes -> `message` with numbered fields
      *   - Sealed traits -> `message` with `oneof`
      *   - Primitives: Int -> sint32, Long -> sint64, Double -> double, Float -> float, String -> string, Boolean -> bool, Short -> sint32,
      *     Byte -> sint32, Char -> sint32
      *   - List[A] / Vector[A] / Seq[A] / Chunk[A] -> `repeated`
      *   - Option[A] -> `optional`
      *   - Nested case classes -> referenced by message name (definitions collected)
      */
    object ProtoSchema:
        /** Generates a `.proto` schema string for type `A`. */
        inline def from[A]: String = fromStructure(Structure.of[A])

        /** Derives a proto schema string from a Structure.Type at runtime. */
        def fromStructure(rt: Structure.Type): String =

            /** Accumulated state threaded through collection. */
            case class State(seen: Set[String], messages: List[String])

            def collect(tpe: Structure.Type.Product | Structure.Type.Sum, state: State): State =
                val name = tpe.name
                if state.seen.contains(name) then state
                else
                    val visited = state.copy(seen = state.seen + name)
                    tpe match
                        case Structure.Type.Sum(name, _, _, variants, _) =>
                            val sb = new StringBuilder
                            sb.append(s"message $name {\n")
                            sb.append("  oneof value {\n")
                            val afterVariants = variants.toList.zipWithIndex.foldLeft(visited) { case (acc, (variant, idx)) =>
                                val nextAcc = variant.variantType match
                                    case ps: (Structure.Type.Product | Structure.Type.Sum) => collect(ps, acc)
                                    case _                                                 => acc
                                val childName = variant.name
                                val fieldName = childName.head.toLower +: childName.tail
                                sb.append(s"    $childName $fieldName = ${idx + 1};\n")
                                nextAcc
                            }
                            sb.append("  }\n")
                            sb.append("}\n")
                            afterVariants.copy(messages = sb.toString :: afterVariants.messages)

                        case Structure.Type.Product(name, _, _, fields) =>
                            val sb = new StringBuilder
                            sb.append(s"message $name {\n")
                            val afterFields = fields.toList.zipWithIndex.foldLeft(visited) { case (acc, (field, idx)) =>
                                val (decl, nextAcc) = protoFieldDecl(field.name, field.fieldType, idx + 1, acc)
                                sb.append(s"  $decl\n")
                                nextAcc
                            }
                            sb.append("}\n")
                            afterFields.copy(messages = sb.toString :: afterFields.messages)
                    end match
                end if
            end collect

            def protoFieldDecl(name: String, tpe: Structure.Type, fieldNumber: Int, state: State): (String, State) =
                tpe match
                    case p: Structure.Type.Primitive =>
                        val typeName = primitiveProtoName(p.kind)
                        (s"$typeName $name = $fieldNumber;", state)

                    case Structure.Type.Optional(_, _, inner) =>
                        inner match
                            case _: Structure.Type.Optional =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support nested Optional (Option[Option[_]]) in field '$name'"
                                )
                            case (_: Structure.Type.Primitive) | (_: Structure.Type.Collection) |
                                (_: Structure.Type.Mapping) | (_: Structure.Type.Product) | (_: Structure.Type.Sum) =>
                                val (innerType, nextState) = protoTypeName(inner, state)
                                (s"optional $innerType $name = $fieldNumber;", nextState)

                    case Structure.Type.Collection(_, _, elem) =>
                        elem match
                            case _: Structure.Type.Optional =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support List[Option[_]] (field '$name'): use a wrapper message instead"
                                )
                            case _: Structure.Type.Collection =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support nested repeated fields (List[List[_]]) in field '$name': use a wrapper message instead"
                                )
                            case _: Structure.Type.Mapping =>
                                throw new IllegalArgumentException(
                                    s"proto3 does not support List[Map[_, _]] (field '$name'): use a wrapper message instead"
                                )
                            case (_: Structure.Type.Primitive) | (_: Structure.Type.Product) | (_: Structure.Type.Sum) =>
                                val (innerType, nextState) = protoTypeName(elem, state)
                                (s"repeated $innerType $name = $fieldNumber;", nextState)

                    case Structure.Type.Mapping(_, _, key, value) =>
                        val (keyName, s1) = protoTypeName(key, state)
                        val (valName, s2) = protoTypeName(value, s1)
                        (s"map<$keyName, $valName> $name = $fieldNumber;", s2)

                    case p: Structure.Type.Product =>
                        val nextState = collect(p, state)
                        (s"${p.name} $name = $fieldNumber;", nextState)

                    case s: Structure.Type.Sum =>
                        val nextState = collect(s, state)
                        (s"${s.name} $name = $fieldNumber;", nextState)
                end match
            end protoFieldDecl

            def protoTypeName(tpe: Structure.Type, state: State): (String, State) =
                tpe match
                    case p: Structure.Type.Primitive =>
                        (primitiveProtoName(p.kind), state)

                    case Structure.Type.Optional(_, _, inner) =>
                        // Optional-as-type-name (inside a repeated/map context) is not valid in proto3
                        throw new IllegalArgumentException(
                            s"proto3 does not support Optional as an element type in a repeated or map field"
                        )

                    case Structure.Type.Collection(_, _, elem) =>
                        // Collection-as-type-name occurs when used inside another repeated/map — not valid in proto3
                        throw new IllegalArgumentException(
                            s"proto3 does not support nested repeated fields (List[List[_]] or map value List[_]): use a wrapper message instead"
                        )

                    case Structure.Type.Mapping(_, _, _, _) =>
                        // Mapping-as-type-name occurs when used as an element inside repeated/map — not valid in proto3
                        throw new IllegalArgumentException(
                            s"proto3 does not support nested Mapping as a type name (map value Map[_, _]): use a wrapper message instead"
                        )

                    case p: Structure.Type.Product =>
                        val nextState = collect(p, state)
                        (p.name, nextState)

                    case s: Structure.Type.Sum =>
                        val nextState = collect(s, state)
                        (s.name, nextState)
                end match
            end protoTypeName

            /** Protobuf type name for a primitive kind.
              *
              * Note: proto3 does not define an arbitrary-precision numeric type. BigInt and BigDecimal are serialized as `string` to
              * preserve the exact value during round-trips. This is a deliberate mapping, not a silent default.
              *
              * Note: proto3 has no Unit (void) scalar type. Unit-typed fields are rejected with an IllegalArgumentException, consistent
              * with the rejection of other incompatible shapes (nested Optional, nested Collection, etc.).
              */
            def primitiveProtoName(kind: Structure.PrimitiveKind): String =
                kind match
                    case Structure.PrimitiveKind.Int | Structure.PrimitiveKind.Short |
                        Structure.PrimitiveKind.Byte | Structure.PrimitiveKind.Char => "sint32"
                    case Structure.PrimitiveKind.Long                                        => "sint64"
                    case Structure.PrimitiveKind.Double                                      => "double"
                    case Structure.PrimitiveKind.Float                                       => "float"
                    case Structure.PrimitiveKind.String                                      => "string"
                    case Structure.PrimitiveKind.Boolean                                     => "bool"
                    case Structure.PrimitiveKind.BigInt | Structure.PrimitiveKind.BigDecimal => "string"
                    case Structure.PrimitiveKind.Unit =>
                        throw new IllegalArgumentException(
                            "Unit-typed fields are not supported in Protobuf; omit the field or wrap in Option[T]"
                        )
                end match
            end primitiveProtoName

            rt match
                case ps: (Structure.Type.Product | Structure.Type.Sum) =>
                    val finalState = collect(ps, State(Set.empty, Nil))
                    val sb         = new StringBuilder
                    sb.append("syntax = \"proto3\";\n\n")
                    finalState.messages.reverse.foreach { msg =>
                        sb.append(msg)
                        sb.append('\n')
                    }
                    sb.toString.trim
                case (_: Structure.Type.Primitive) | (_: Structure.Type.Collection) |
                    (_: Structure.Type.Optional) | (_: Structure.Type.Mapping) =>
                    throw new IllegalArgumentException(
                        s"Protobuf.protoSchema requires a case class or sealed trait as the top-level type, got: ${rt.name}"
                    )
            end match
        end fromStructure

    end ProtoSchema

end Protobuf
