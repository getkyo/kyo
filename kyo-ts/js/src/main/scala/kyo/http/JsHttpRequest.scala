package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpRequest")
class JsHttpRequest[Fields](@JSName("$http") val underlying: HttpRequest[Fields]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def addField[N, V](name: N, value: V) =
        new JsHttpRequest(underlying.addField(name, value))

    def addFields[Fields2](r: JsRecord[Fields2]) =
        new JsHttpRequest(underlying.addFields(r.underlying))

    def addHeader(name: Predef.String, value: Predef.String) =
        new JsHttpRequest(underlying.addHeader(name, value))

    def fields() =
        new JsRecord(underlying.fields)

    def headers() =
        new JsHttpHeaders(underlying.headers)

    def method() =
        new JsHttpMethod(underlying.method)

    def path() =
        underlying.path

    def query(name: Predef.String) =
        new JsMaybe(underlying.query(name))

    def queryAll(name: Predef.String) =
        underlying.queryAll(name)

    def setHeader(name: Predef.String, value: Predef.String) =
        new JsHttpRequest(underlying.setHeader(name, value))

    def url() =
        new JsHttpUrl(underlying.url)


end JsHttpRequest