package kyo.llm.json

import zio.schema.{Schema => ZSchema, _}
import zio.json._
import zio.json.ast._
import zio.json.internal.Write
import scala.annotation.StaticAnnotation
import zio.Chunk

case class Schema(data: List[(String, Json)])

object Schema {

  case class Const[T](v: T)

  implicit val jsonSchemaEncoder: JsonEncoder[Schema] = new JsonEncoder[Schema] {
    override def unsafeEncode(js: Schema, indent: Option[Int], out: Write): Unit = {
      implicitly[JsonEncoder[Json.Obj]].unsafeEncode(Json.Obj(js.data.toSeq: _*), indent, out)
    }
  }

  def apply(schema: ZSchema[_]): Schema =
    new Schema(convert(schema))

  def desc(c: Chunk[Any]): List[(String, Json)] =
    c.collect {
      case desc(v) =>
        "description" -> Json.Str(v)
    }.distinct.toList

  def convert(schema: ZSchema[_]): List[(String, Json)] = {
    def desc = this.desc(schema.annotations)
    schema match {

      case ZSchema.Primitive(StandardType.StringType, Chunk(Const(v))) =>
        desc ++ List(
            "type" -> Json.Str("string"),
            "enum" -> Json.Arr(Json.Str(v.asInstanceOf[String]))
        )

      case ZSchema.Primitive(StandardType.StringType, _) =>
        desc ++ List("type" -> Json.Str("string"))

      case ZSchema.Primitive(StandardType.IntType, Chunk(Const(v))) =>
        desc ++ List(
            "type" -> Json.Str("integer"),
            "enum" -> Json.Arr(Json.Num(v.asInstanceOf[Int]))
        )

      case ZSchema.Primitive(StandardType.IntType, _) =>
        desc ++ List("type" -> Json.Str("integer"), "format" -> Json.Str("int32"))

      case ZSchema.Primitive(StandardType.LongType, Chunk(Const(v))) =>
        desc ++ List(
            "type" -> Json.Str("integer"),
            "enum" -> Json.Arr(Json.Num(v.asInstanceOf[Long]))
        )

      case ZSchema.Primitive(StandardType.LongType, _) =>
        desc ++ List("type" -> Json.Str("integer"), "format" -> Json.Str("int64"))

      case ZSchema.Primitive(StandardType.DoubleType, Chunk(Const(v))) =>
        desc ++ List(
            "type" -> Json.Str("number"),
            "enum" -> Json.Arr(Json.Num(v.asInstanceOf[Double]))
        )

      case ZSchema.Primitive(StandardType.DoubleType, _) =>
        desc ++ List("type" -> Json.Str("number"))

      case ZSchema.Primitive(StandardType.FloatType, Chunk(Const(v))) =>
        desc ++ List(
            "type" -> Json.Str("number"),
            "enum" -> Json.Arr(Json.Num(v.asInstanceOf[Float]))
        )

      case ZSchema.Primitive(StandardType.FloatType, _) =>
        desc ++ List("type" -> Json.Str("number"), "format" -> Json.Str("float"))

      case ZSchema.Primitive(StandardType.BoolType, Chunk(Const(v))) =>
        desc ++ List(
            "type" -> Json.Str("boolean"),
            "enum" -> Json.Arr(Json.Bool(v.asInstanceOf[Boolean]))
        )

      case ZSchema.Primitive(StandardType.BoolType, _) =>
        desc ++ List("type" -> Json.Str("boolean"))

      case ZSchema.Optional(innerSchema, _) =>
        convert(innerSchema)

      case ZSchema.Sequence(innerSchema, _, _, _, _) =>
        List("type" -> Json.Str("array"), "items" -> Json.Obj(convert(innerSchema): _*))

      case schema: ZSchema.Enum[_] =>
        val cases = schema.cases.map { c =>
          val caseProperties = c.schema match {
            case record: ZSchema.Record[_] =>
              val fields = record.fields.map { field =>
                field.name -> Json.Obj(convert(field.schema): _*)
              }
              val requiredFields = record.fields.collect {
                case field if !field.schema.isInstanceOf[ZSchema.Optional[_]] =>
                  Json.Str(field.name)
              }
              Json.Obj(
                  "type"       -> Json.Str("object"),
                  "properties" -> Json.Obj(fields: _*),
                  "required"   -> Json.Arr(requiredFields: _*)
              )
            case _ =>
              throw new UnsupportedOperationException("Non-record enum case is not supported")
          }
          c.id -> caseProperties
        }
        desc ++ List(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(cases: _*)
        )

      case schema: ZSchema.Record[_] =>
        val properties = schema.fields.foldLeft(List.empty[(String, Json)]) { (acc, field) =>
          acc :+ (field.name -> Json.Obj(
              (this.desc(field.annotations) ++ convert(field.schema)): _*
          ))
        }
        val requiredFields = schema.fields.collect {
          case field if !field.schema.isInstanceOf[ZSchema.Optional[_]] => Json.Str(field.name)
        }
        desc ++ List(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(properties.toSeq: _*),
            "required"   -> Json.Arr(requiredFields: _*)
        )

      case ZSchema.Map(keySchema, valueSchema, _) =>
        keySchema match {
          case ZSchema.Primitive(tpe, _) if (tpe == StandardType.StringType) =>
            List(
                "type"                 -> Json.Str("object"),
                "additionalProperties" -> Json.Obj(convert(valueSchema): _*)
            )
          case _ =>
            throw new UnsupportedOperationException("Non-string map keys are not supported")
        }

      case schema: ZSchema.Lazy[_] =>
        convert(schema.schema)

      case _ =>
        throw new UnsupportedOperationException("This schema type is not supported: " + schema)
    }
  }
}
