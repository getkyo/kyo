package kyo

import java.util.UUID

/** Type-safe string codec for parsing and serializing individual HTTP parameter values.
  *
  * Used by `HttpPath.Capture`, `HttpRoute` query/header/cookie parameters, and `HttpClient` route-based calls to convert between typed
  * values and their string representations in URLs, headers, and cookies. Given instances are provided for common types (`Int`, `Long`,
  * `String`, `Boolean`, `Double`, `Float`, `UUID`).
  *
  * @tparam A
  *   the type to parse from and serialize to strings
  * @see
  *   [[kyo.HttpPath.Capture]]
  * @see
  *   [[kyo.HttpRoute]]
  */
trait HttpParamCodec[A]:
    def parse(s: String): A
    def serialize(a: A): String

object HttpParamCodec:
    def apply[A](parse: String => A, serialize: A => String): HttpParamCodec[A] =
        val p = parse; val s = serialize
        new HttpParamCodec[A]:
            def parse(str: String) = p(str)
            def serialize(a: A)    = s(a)
    end apply

    given HttpParamCodec[Int]     = HttpParamCodec(_.toInt, _.toString)
    given HttpParamCodec[Long]    = HttpParamCodec(_.toLong, _.toString)
    given HttpParamCodec[String]  = HttpParamCodec(identity, identity)
    given HttpParamCodec[Boolean] = HttpParamCodec(_.toBoolean, _.toString)
    given HttpParamCodec[Double]  = HttpParamCodec(_.toDouble, _.toString)
    given HttpParamCodec[Float]   = HttpParamCodec(_.toFloat, _.toString)
    given HttpParamCodec[UUID]    = HttpParamCodec(UUID.fromString, _.toString)
end HttpParamCodec
