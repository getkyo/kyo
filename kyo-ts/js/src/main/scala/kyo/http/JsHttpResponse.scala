package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpResponse")
class JsHttpResponse[Fields](@JSName("$http") val underlying: HttpResponse[Fields]) extends js.Object:
    import kyo.JsFacadeGivens.given
    def addField[N, V](name: N, value: V) =
        new JsHttpResponse(underlying.addField(name, value))

    def addFields[Fields2](r: JsRecord[Fields2]) =
        new JsHttpResponse(underlying.addFields(r.underlying))

    def addHeader(name: Predef.String, value: Predef.String) =
        new JsHttpResponse(underlying.addHeader(name, value))

    def cacheControl(directive: Predef.String) =
        new JsHttpResponse(underlying.cacheControl(directive))

    def contentDisposition(filename: Predef.String, isInline: Boolean) =
        new JsHttpResponse(underlying.contentDisposition(filename, isInline))

    def etag(value: Predef.String) =
        new JsHttpResponse(underlying.etag(value))

    def fields() =
        new JsRecord(underlying.fields)

    def halt() =
        new JsKyo(HttpResponse.halt(underlying))

    def headers() =
        new JsHttpHeaders(underlying.headers)

    def noCache() =
        new JsHttpResponse(underlying.noCache)

    def noStore() =
        new JsHttpResponse(underlying.noStore)

    def setHeader(name: Predef.String, value: Predef.String) =
        new JsHttpResponse(underlying.setHeader(name, value))

    def status() =
        new JsHttpStatus(underlying.status)


end JsHttpResponse

object JsHttpResponse:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def accepted() =
        new JsHttpResponse(HttpResponse.accepted)

    @JSExportStatic
    def acceptedJson[A](body: A) =
        new JsHttpResponse(HttpResponse.acceptedJson(body))

    @JSExportStatic
    def badRequest() =
        new JsHttpResponse(HttpResponse.badRequest)

    @JSExportStatic
    def badRequestJson[A](body: A) =
        new JsHttpResponse(HttpResponse.badRequestJson(body))

    @JSExportStatic
    def conflict() =
        new JsHttpResponse(HttpResponse.conflict)

    @JSExportStatic
    def conflictJson[A](body: A) =
        new JsHttpResponse(HttpResponse.conflictJson(body))

    @JSExportStatic
    def created() =
        new JsHttpResponse(HttpResponse.created)

    @JSExportStatic
    def createdJson[A](body: A) =
        new JsHttpResponse(HttpResponse.createdJson(body))

    @JSExportStatic
    def forbidden() =
        new JsHttpResponse(HttpResponse.forbidden)

    @JSExportStatic
    def forbiddenJson[A](body: A) =
        new JsHttpResponse(HttpResponse.forbiddenJson(body))

    @JSExportStatic
    def noContent() =
        new JsHttpResponse(HttpResponse.noContent)

    @JSExportStatic
    def notFound() =
        new JsHttpResponse(HttpResponse.notFound)

    @JSExportStatic
    def notFoundJson[A](body: A) =
        new JsHttpResponse(HttpResponse.notFoundJson(body))

    @JSExportStatic
    def notModified() =
        new JsHttpResponse(HttpResponse.notModified)

    @JSExportStatic
    def ok() =
        new JsHttpResponse(HttpResponse.ok)

    @JSExportStatic
    def okJson[A](body: A) =
        new JsHttpResponse(HttpResponse.okJson(body))

    @JSExportStatic
    def serverError() =
        new JsHttpResponse(HttpResponse.serverError)

    @JSExportStatic
    def serverErrorJson[A](body: A) =
        new JsHttpResponse(HttpResponse.serverErrorJson(body))

    @JSExportStatic
    def serviceUnavailable() =
        new JsHttpResponse(HttpResponse.serviceUnavailable)

    @JSExportStatic
    def serviceUnavailableJson[A](body: A) =
        new JsHttpResponse(HttpResponse.serviceUnavailableJson(body))

    @JSExportStatic
    def tooManyRequests() =
        new JsHttpResponse(HttpResponse.tooManyRequests)

    @JSExportStatic
    def tooManyRequestsJson[A](body: A) =
        new JsHttpResponse(HttpResponse.tooManyRequestsJson(body))

    @JSExportStatic
    def unauthorized() =
        new JsHttpResponse(HttpResponse.unauthorized)

    @JSExportStatic
    def unauthorizedJson[A](body: A) =
        new JsHttpResponse(HttpResponse.unauthorizedJson(body))

    @JSExportStatic
    def unprocessableEntity() =
        new JsHttpResponse(HttpResponse.unprocessableEntity)

    @JSExportStatic
    def unprocessableEntityJson[A](body: A) =
        new JsHttpResponse(HttpResponse.unprocessableEntityJson(body))


end JsHttpResponse