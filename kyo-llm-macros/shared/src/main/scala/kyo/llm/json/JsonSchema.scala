package kyo.llm.json

import zio.schema.{Schema as ZSchema, *}
import zio.json.*
import zio.json.ast.*
import zio.json.internal.Write
import scala.annotation.StaticAnnotation
import zio.Chunk

case class Schema(data: List[(String, Json)])

object Schema:

    case class Const[T](v: T)

    given jsonSchemaEncoder: JsonEncoder[Schema] = new JsonEncoder[Schema]:
        override def unsafeEncode(js: Schema, indent: Option[Int], out: Write): Unit =
            implicitly[JsonEncoder[Json.Obj]].unsafeEncode(Json.Obj(js.data.toSeq*), indent, out)

    def apply(schema: ZSchema[?]): Schema =
        new Schema(convert(schema))

    def desc(c: Chunk[Any], s: String = ""): List[(String, Json)] =
        c.collect {
            case doc(v) =>
                "description" -> Json.Str(v + (if s.isEmpty then "" else "\n" + s))
        }.distinct.toList match
            case Nil if (!s.isEmpty) =>
                List("description" -> Json.Str(s))
            case l =>
                l

    def convert(schema: ZSchema[?]): List[(String, Json)] =
        def desc(s: String = "") = this.desc(schema.annotations, s)
        ZSchema.force(schema) match

            case ZSchema.Primitive(StandardType.StringType, Chunk(Const(v))) =>
                desc() ++ List(
                    "type" -> Json.Str("string"),
                    "enum" -> Json.Arr(Json.Str(v.asInstanceOf[String]))
                )

            case ZSchema.Primitive(StandardType.StringType, _) =>
                desc() ++ List("type" -> Json.Str("string"))

            case ZSchema.Primitive(StandardType.IntType, Chunk(Const(v))) =>
                desc() ++ List(
                    "type" -> Json.Str("integer"),
                    "enum" -> Json.Arr(Json.Num(v.asInstanceOf[Int]))
                )

            case ZSchema.Primitive(StandardType.IntType, _) =>
                desc() ++ List("type" -> Json.Str("integer"), "format" -> Json.Str("int32"))

            case ZSchema.Primitive(StandardType.LongType, Chunk(Const(v))) =>
                desc() ++ List(
                    "type" -> Json.Str("integer"),
                    "enum" -> Json.Arr(Json.Num(v.asInstanceOf[Long]))
                )

            case ZSchema.Primitive(StandardType.LongType, _) =>
                desc() ++ List("type" -> Json.Str("integer"), "format" -> Json.Str("int64"))

            case ZSchema.Primitive(StandardType.DoubleType, Chunk(Const(v))) =>
                desc() ++ List(
                    "type" -> Json.Str("number"),
                    "enum" -> Json.Arr(Json.Num(v.asInstanceOf[Double]))
                )

            case ZSchema.Primitive(StandardType.DoubleType, _) =>
                desc() ++ List("type" -> Json.Str("number"))

            case ZSchema.Primitive(StandardType.FloatType, Chunk(Const(v))) =>
                desc() ++ List(
                    "type" -> Json.Str("number"),
                    "enum" -> Json.Arr(Json.Num(v.asInstanceOf[Float]))
                )

            case ZSchema.Primitive(StandardType.FloatType, _) =>
                desc() ++ List("type" -> Json.Str("number"), "format" -> Json.Str("float"))

            case ZSchema.Primitive(StandardType.BoolType, Chunk(Const(v))) =>
                desc() ++ List(
                    "type" -> Json.Str("boolean"),
                    "enum" -> Json.Arr(Json.Bool(v.asInstanceOf[Boolean]))
                )

            case ZSchema.Primitive(StandardType.BoolType, _) =>
                desc() ++ List("type" -> Json.Str("boolean"))

            case ZSchema.Optional(innerSchema, _) =>
                convert(innerSchema)

            case ZSchema.Sequence(innerSchema, _, _, _, _) =>
                desc("This is a **json array**, do not generate an object.") ++
                    List("type" -> Json.Str("array"), "items" -> Json.Obj(convert(innerSchema)*))

            case schema: ZSchema.Enum[?] =>
                val cases = schema.cases.map { c =>
                    val caseProperties = c.schema match
                        case record: ZSchema.Record[?] =>
                            val fields = record.fields.map { field =>
                                field.name -> Json.Obj(convert(field.schema)*)
                            }
                            val requiredFields = record.fields.collect {
                                case field if !field.schema.isInstanceOf[ZSchema.Optional[_]] =>
                                    Json.Str(field.name)
                            }
                            Json.Obj(
                                "type"       -> Json.Str("object"),
                                "properties" -> Json.Obj(fields*),
                                "required"   -> Json.Arr(requiredFields*)
                            )
                        case _ =>
                            throw new UnsupportedOperationException(
                                "Non-record enum case is not supported"
                            )
                    c.id -> caseProperties
                }
                desc() ++ List(
                    "type"       -> Json.Str("object"),
                    "properties" -> Json.Obj(cases*)
                )

            case schema: ZSchema.Record[?] =>
                val properties =
                    schema.fields.foldLeft(List.empty[(String, Json)]) { (acc, field) =>
                        acc :+ (field.name -> Json.Obj(
                            (this.desc(field.annotations) ++ convert(field.schema))*
                        ))
                    }
                val requiredFields = schema.fields.collect {
                    case field if !field.schema.isInstanceOf[ZSchema.Optional[_]] =>
                        Json.Str(field.name)
                }
                desc() ++ List(
                    "type"       -> Json.Str("object"),
                    "properties" -> Json.Obj(properties.toSeq*),
                    "required"   -> Json.Arr(requiredFields*)
                )

            case ZSchema.Map(keySchema, valueSchema, _) =>
                ZSchema.force(keySchema) match
                    case ZSchema.Primitive(tpe, _) if (tpe == StandardType.StringType) =>
                        List(
                            "type"                 -> Json.Str("object"),
                            "additionalProperties" -> Json.Obj(convert(valueSchema)*)
                        )
                    case _ =>
                        throw new UnsupportedOperationException(
                            "Non-string map keys are not supported"
                        )

            case ZSchema.Transform(schema, f, g, ann, id) =>
                convert(schema)

            case schema =>
                throw new UnsupportedOperationException(
                    "This schema type is not supported: " + schema
                )
        end match
    end convert
end Schema
