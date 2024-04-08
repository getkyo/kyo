package kyo

import izumi.reflect.dottyreflection.TypeInspections
import izumi.reflect.macrortti.LightTypeTag
import scala.quoted.*

opaque type Tag[T] = String

object Tag:

    extension [T](t: Tag[T])
        def parse: LightTypeTag =
            val arr = t.split('|')
            LightTypeTag.parse(arr(0).toInt, arr(1), arr(2), arr(3).toInt)
    end extension

    inline given apply[T]: Tag[T] = ${ tagImpl[T] }

    private def tagImpl[T: Type](using Quotes): Expr[Tag[T]] =
        import quotes.reflect.*
        if TypeRepr.of[T] =:= TypeRepr.of[Nothing] then
            report.errorAndAbort("Can't infer a Tag[Nothing]")
        if TypeRepr.of[T] =:= TypeRepr.of[Any] then
            report.errorAndAbort("Can't infer a Tag[Any]")
        if TypeRepr.of[T] =:= TypeRepr.of[Object] then
            report.errorAndAbort("Can't infer a Tag[Object]")
        val ref         = TypeInspections.apply[T]
        val fullDb      = TypeInspections.fullDb[T]
        val nameDb      = TypeInspections.unappliedDb[T]
        val ltt         = LightTypeTag(ref, fullDb, nameDb)
        val serialized  = ltt.serialize()
        val hashCodeRef = serialized.hash
        val strRef      = serialized.ref
        val strDBs      = serialized.databases
        val version     = LightTypeTag.currentBinaryFormatVersion
        val tagStr      = s"$hashCodeRef|$strRef|$strDBs|$version"
        Expr(tagStr)
    end tagImpl
end Tag
