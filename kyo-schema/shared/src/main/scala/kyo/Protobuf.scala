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

/** Entry point for Protocol Buffers binary serialization and schema generation.
  *
  * All methods are inline and require a `Schema[A]` instance in scope. Encoding produces proto3 wire-format bytes. Field numbers are
  * derived from stable MurmurHash3 hashes of field names, enabling schema evolution without breaking existing encoded data.
  *
  * @see
  *   [[kyo.Schema]] for the type-driven serialization model
  * @see
  *   [[kyo.Json]] for JSON serialization
  */
object Protobuf:
    given Protobuf = Protobuf()

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
              * with Phase 8's rejection of other incompatible shapes (nested Optional, nested Collection, etc.).
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
