package kyo

import java.util.UUID
import scala.annotation.nowarn

/** String-based codec for converting HTTP wire values to and from typed Scala values.
  *
  * HttpCodec powers all non-JSON serialization in routes: path captures, query parameters, headers, and cookies all use it for encoding and
  * decoding. Built-in instances cover `Int`, `Long`, `Short`, `Byte`, `String`, `Boolean`, `Double`, `Float`, `BigDecimal`, `BigInt`,
  * `UUID`, `Duration`, and `Instant`.
  *
  * @tparam A
  *   the type to encode/decode
  *
  * @see
  *   [[kyo.HttpPath.Capture]] Uses HttpCodec for path segment parsing
  * @see
  *   [[kyo.HttpRoute.RequestDef]] Uses HttpCodec for query/header/cookie extraction
  * @see
  *   [[kyo.Schema]] The separate codec for JSON body serialization
  */
abstract class HttpCodec[A]:
    def encode(value: A): String
    def decode(raw: String): Result[Throwable, A]

object HttpCodec:

    @nowarn("msg=anonymous")
    inline def apply[A](inline enc: A => String, inline dec: String => A): HttpCodec[A] =
        val e = enc; val d = dec
        new HttpCodec[A]:
            def encode(value: A)                          = e(value)
            def decode(raw: String): Result[Throwable, A] = Result.catching[Throwable](d(raw))
        end new
    end apply

    given HttpCodec[Short]      = HttpCodec(_.toString, _.toShort)
    given HttpCodec[Byte]       = HttpCodec(_.toString, _.toByte)
    given HttpCodec[Int]        = HttpCodec(_.toString, _.toInt)
    given HttpCodec[Long]       = HttpCodec(_.toString, _.toLong)
    given HttpCodec[String]     = HttpCodec(identity, identity)
    given HttpCodec[Boolean]    = HttpCodec(_.toString, _.toBoolean)
    given HttpCodec[Double]     = HttpCodec(_.toString, _.toDouble)
    given HttpCodec[Float]      = HttpCodec(_.toString, _.toFloat)
    given HttpCodec[BigDecimal] = HttpCodec(_.toString, BigDecimal(_))
    given HttpCodec[BigInt]     = HttpCodec(_.toString, BigInt(_))
    given HttpCodec[UUID]       = HttpCodec(_.toString, UUID.fromString)
    given HttpCodec[Duration]   = HttpCodec(_.show, s => Duration.parse(s)(using Frame.internal).getOrThrow)
    given HttpCodec[Instant]    = HttpCodec(_.show, s => Instant.parse(s).getOrThrow)

end HttpCodec
