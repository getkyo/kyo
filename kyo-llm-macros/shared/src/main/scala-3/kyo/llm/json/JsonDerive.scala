package kyo.llm.json

import kyo._
import kyo.ios._
import zio.schema.codec.JsonCodec
import scala.compiletime._
import zio.schema.{Schema => ZSchema, _}
import zio.Chunk

trait JsonDerive {

  inline implicit def deriveJson[T]: Json[T] = {
    import JsonDerive._
    Json.fromZio(DeriveSchema.gen)
  }
}

object JsonDerive {
  inline implicit def constStringZSchema[T <: String]: ZSchema[T] =
    const(StandardType.StringType, compiletime.constValue[T])

  inline implicit def constIntZSchema[T <: Int]: ZSchema[T] =
    const(StandardType.IntType, compiletime.constValue[T])

  inline implicit def constLongZSchema[T <: Long]: ZSchema[T] =
    const(StandardType.LongType, compiletime.constValue[T])

  inline implicit def constDoubleZSchema[T <: Double]: ZSchema[T] =
    const(StandardType.DoubleType, compiletime.constValue[T])

  inline implicit def constFloatZSchema[T <: Float]: ZSchema[T] =
    const(StandardType.FloatType, compiletime.constValue[T])

  inline implicit def constBoolZSchema[T <: Boolean]: ZSchema[T] =
    const(StandardType.BoolType, compiletime.constValue[T])

  private def const[T](t: StandardType[_], v: Any): ZSchema[T] =
    ZSchema.Primitive(t, Chunk(Schema.Const(v))).asInstanceOf[ZSchema[T]]
}
