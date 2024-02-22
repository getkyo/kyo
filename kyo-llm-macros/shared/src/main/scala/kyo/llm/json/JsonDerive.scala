package kyo.llm.json

import kyo.*

import zio.schema.codec.JsonCodec
import scala.compiletime.*
import zio.schema.{Schema as ZSchema, *}
import zio.Chunk

trait JsonDerive:

    inline given deriveJson[T]: Json[T] =
        import JsonDerive.*
        Json.fromZio(DeriveSchema.gen)
end JsonDerive

object JsonDerive:
    inline given constStringZSchema[T <: String]: ZSchema[T] =
        const(StandardType.StringType, compiletime.constValue[T])

    inline given constIntZSchema[T <: Int]: ZSchema[T] =
        const(StandardType.IntType, compiletime.constValue[T])

    inline given constLongZSchema[T <: Long]: ZSchema[T] =
        const(StandardType.LongType, compiletime.constValue[T])

    inline given constDoubleZSchema[T <: Double]: ZSchema[T] =
        const(StandardType.DoubleType, compiletime.constValue[T])

    inline given constFloatZSchema[T <: Float]: ZSchema[T] =
        const(StandardType.FloatType, compiletime.constValue[T])

    inline given constBoolZSchema[T <: Boolean]: ZSchema[T] =
        const(StandardType.BoolType, compiletime.constValue[T])

    private def const[T](t: StandardType[?], v: Any): ZSchema[T] =
        ZSchema.Primitive(t, Chunk(Schema.Const(v))).asInstanceOf[ZSchema[T]]
end JsonDerive
