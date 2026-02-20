package kyo.http2

import java.util.UUID
import scala.annotation.nowarn

abstract class HttpCodec[A]:
    def encode(value: A): String
    def decode(raw: String): A

object HttpCodec:

    @nowarn("msg=anonymous")
    inline def apply[A](inline enc: A => String, inline dec: String => A): HttpCodec[A] =
        val e = enc; val d = dec
        new HttpCodec[A]:
            def encode(value: A)    = e(value)
            def decode(raw: String) = d(raw)
    end apply

    given HttpCodec[Int]     = HttpCodec(_.toString, _.toInt)
    given HttpCodec[Long]    = HttpCodec(_.toString, _.toLong)
    given HttpCodec[String]  = HttpCodec(identity, identity)
    given HttpCodec[Boolean] = HttpCodec(_.toString, _.toBoolean)
    given HttpCodec[Double]  = HttpCodec(_.toString, _.toDouble)
    given HttpCodec[Float]   = HttpCodec(_.toString, _.toFloat)
    given HttpCodec[UUID]    = HttpCodec(_.toString, UUID.fromString)

end HttpCodec
