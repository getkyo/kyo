package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("TTable")
class JsTTable[Fields](@JSName("$ttab") val underlying: TTable[Fields]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def get(id: JsId) =
        new JsKyo(underlying.get(id.underlying))

    def insert(record: JsRecord[Fields]) =
        new JsKyo(underlying.insert(record.underlying))

    def isEmpty() =
        new JsKyo(underlying.isEmpty)

    def remove(id: JsId) =
        new JsKyo(underlying.remove(id.underlying))

    def size() =
        new JsKyo(underlying.size)

    def snapshot() =
        new JsKyo(underlying.snapshot)

    def unsafeId(id: Int) =
        new JsId(underlying.unsafeId(id))

    def update(id: JsId, record: JsRecord[Fields]) =
        new JsKyo(underlying.update(id.underlying, record.underlying))

    def upsert(id: JsId, record: JsRecord[Fields]) =
        new JsKyo(underlying.upsert(id.underlying, record.underlying))


end JsTTable