package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Record")
class JsRecord[F](@JSName("$reco") val underlying: Record[F]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def and[A](other: JsRecord[A]) =
        new JsRecord(underlying.`&`(other.underlying))

    def compact() =
        new JsRecord(underlying.compact)

    def fields() =
        underlying.fields

    def getField[Name, V](name: Name) =
        underlying.getField(name)

    def selectDynamic[Name](name: Name) =
        underlying.selectDynamic(name)

    def size() =
        underlying.size

    def toDict() =
        new JsDict(underlying.toDict)

    def widen[A, B]() =
        new JsRecord(Record.widen(underlying))

    def zip[F2](other: JsRecord[F2]) =
        new JsRecord(underlying.zip(other.underlying))


end JsRecord

object JsRecord:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def empty() =
        new JsRecord(Record.empty)

    @JSExportStatic
    def field() =
        new JsField(Record.`~`)

    @JSExportStatic
    def given_CanEqual_Record_Record[F]() =
        Record.given_CanEqual_Record_Record

    @JSExportStatic
    def render[F]() =
        new JsRender(Record.render)

    @JSExportStatic
    def stage[A]() =
        Record.stage


end JsRecord