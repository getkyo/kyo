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
        maxDepth: Int = 512,
        maxCollectionSize: Int = 100000
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

            def collect(tpe: Structure.Type, state: State): State =
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
                                val nextAcc   = collect(variant.variantType, acc)
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

                        case _ => visited
                    end match
                end if
            end collect

            def protoFieldDecl(name: String, tpe: Structure.Type, fieldNumber: Int, state: State): (String, State) =
                tpe match
                    case p: Structure.Type.Primitive =>
                        val typeName = primitiveProtoName(p.name)
                        (s"$typeName $name = $fieldNumber;", state)

                    case Structure.Type.Optional(_, _, inner) =>
                        val (innerType, nextState) = protoTypeName(inner, state)
                        (s"optional $innerType $name = $fieldNumber;", nextState)

                    case Structure.Type.Collection(_, _, elem) =>
                        val (innerType, nextState) = protoTypeName(elem, state)
                        (s"repeated $innerType $name = $fieldNumber;", nextState)

                    case Structure.Type.Product(typeName, _, _, _) =>
                        val nextState = collect(tpe, state)
                        (s"$typeName $name = $fieldNumber;", nextState)

                    case Structure.Type.Sum(typeName, _, _, _, _) =>
                        val nextState = collect(tpe, state)
                        (s"$typeName $name = $fieldNumber;", nextState)

                    case _ =>
                        val nextState = collect(tpe, state)
                        (s"${tpe.name} $name = $fieldNumber;", nextState)
                end match
            end protoFieldDecl

            def protoTypeName(tpe: Structure.Type, state: State): (String, State) =
                tpe match
                    case p: Structure.Type.Primitive =>
                        (primitiveProtoName(p.name), state)
                    case _ =>
                        val nextState = collect(tpe, state)
                        (tpe.name, nextState)
                end match
            end protoTypeName

            def primitiveProtoName(name: String): String =
                name match
                    case "Int"     => "sint32"
                    case "Long"    => "sint64"
                    case "Double"  => "double"
                    case "Float"   => "float"
                    case "String"  => "string"
                    case "Boolean" => "bool"
                    case "Short"   => "sint32"
                    case "Byte"    => "sint32"
                    case "Char"    => "sint32"
                    case _         => "string"
                end match
            end primitiveProtoName

            val finalState = collect(rt, State(Set.empty, Nil))
            val sb         = new StringBuilder
            sb.append("syntax = \"proto3\";\n\n")
            finalState.messages.reverse.foreach { msg =>
                sb.append(msg)
                sb.append('\n')
            }
            sb.toString.trim
        end fromStructure

    end ProtoSchema

end Protobuf
