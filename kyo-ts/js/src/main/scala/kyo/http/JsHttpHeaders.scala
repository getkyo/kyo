package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("HttpHeaders")
class JsHttpHeaders(@JSName("$http") val underlying: HttpHeaders) extends js.Object:
    import kyo.JsFacadeGivens.given
    def add(name: Predef.String, value: Predef.String) =
        new JsHttpHeaders(HttpHeaders.add(underlying)(name, value))

    def addCookie[A](name: Predef.String, cookie: JsHttpCookie[A]) =
        new JsHttpHeaders(HttpHeaders.addCookie(underlying)(name, cookie.underlying))

    def concat(other: JsHttpHeaders) =
        new JsHttpHeaders(HttpHeaders.concat(underlying)(other.underlying))

    def contains(name: Predef.String) =
        HttpHeaders.contains(underlying)(name)

    def cookie(name: Predef.String) =
        new JsMaybe(HttpHeaders.cookie(underlying)(name))

    def cookie(name: Predef.String, strict: Boolean) =
        new JsMaybe(HttpHeaders.cookie(underlying)(name, strict))

    def cookies(strict: Boolean) =
        HttpHeaders.cookies(underlying)(strict)

    def cookies() =
        HttpHeaders.cookies(underlying)

    def foldLeft[A](init: A, f: Function3[A, Predef.String, Predef.String, A]) =
        HttpHeaders.foldLeft(underlying)(init)(f)

    def foreach(f: Function2[Predef.String, Predef.String, Unit]) =
        HttpHeaders.foreach(underlying)(f)

    def get(name: Predef.String) =
        new JsMaybe(HttpHeaders.get(underlying)(name))

    def getAll(name: Predef.String) =
        HttpHeaders.getAll(underlying)(name)

    def isEmpty() =
        HttpHeaders.isEmpty(underlying)

    def nonEmpty() =
        HttpHeaders.nonEmpty(underlying)

    def remove(name: Predef.String) =
        new JsHttpHeaders(HttpHeaders.remove(underlying)(name))

    def responseCookie(name: Predef.String) =
        new JsMaybe(HttpHeaders.responseCookie(underlying)(name))

    def set(name: Predef.String, value: Predef.String) =
        new JsHttpHeaders(HttpHeaders.set(underlying)(name, value))

    def size() =
        HttpHeaders.size(underlying)


end JsHttpHeaders

object JsHttpHeaders:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def empty() =
        new JsHttpHeaders(HttpHeaders.empty)

    @JSExportStatic
    def given_CanEqual_HttpHeaders_HttpHeaders() =
        HttpHeaders.given_CanEqual_HttpHeaders_HttpHeaders


end JsHttpHeaders