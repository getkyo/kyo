package kyo.chatgpt.util

import zio.schema._
import zio.json._
import zio.json.ast._
import zio.json.internal.Write

case class JsonSchema(data: Map[String, Json])

object JsonSchema {

  implicit val jsonSchemaEncoder: JsonEncoder[JsonSchema] = new JsonEncoder[JsonSchema] {
    override def unsafeEncode(js: JsonSchema, indent: Option[Int], out: Write): Unit = {
      implicitly[JsonEncoder[Json.Obj]].unsafeEncode(Json.Obj(js.data.toSeq: _*), indent, out)
    }
  }

  def apply(schema: Schema[_]): JsonSchema =
    new JsonSchema(convert(schema))

  def convert(schema: Schema[_]): Map[String, Json] = {
    schema match {
      case Schema.Primitive(StandardType.StringType, _) =>
        Map("type" -> Json.Str("string"))

      case Schema.Primitive(StandardType.IntType, _) =>
        Map("type" -> Json.Str("integer"), "format" -> Json.Str("int32"))

      case Schema.Primitive(StandardType.LongType, _) =>
        Map("type" -> Json.Str("integer"), "format" -> Json.Str("int64"))

      case Schema.Primitive(StandardType.DoubleType, _) =>
        Map("type" -> Json.Str("number"))

      case Schema.Primitive(StandardType.FloatType, _) =>
        Map("type" -> Json.Str("number"), "format" -> Json.Str("float"))

      case Schema.Primitive(StandardType.BoolType, _) =>
        Map("type" -> Json.Str("boolean"))

      case Schema.Optional(innerSchema, _) =>
        convert(innerSchema)

      case Schema.Sequence(innerSchema, _, _, _, _) =>
        Map("type" -> Json.Str("array"), "items" -> Json.Obj(convert(innerSchema).toSeq: _*))

      case schema: Schema.Enum[_] =>
        val cases = schema.cases.map(c => Json.Obj(convert(c.schema).toSeq: _*))
        Map("oneOf" -> Json.Arr(cases: _*))

      case schema: Schema.Record[_] =>
        val properties = schema.fields.foldLeft(Map.empty[String, Json]) { (acc, field) =>
          acc + (field.name -> Json.Obj(convert(field.schema).toSeq: _*))
        }
        val requiredFields = schema.fields.collect {
          case field if !field.schema.isInstanceOf[Schema.Optional[_]] => Json.Str(field.name)
        }
        Map(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(properties.toSeq: _*),
            "required"   -> Json.Arr(requiredFields: _*)
        )

      case schema: Schema.Lazy[_] =>
        convert(schema.schema)

      case _ =>
        throw new UnsupportedOperationException("This schema type is not supported")
    }
  }
}
