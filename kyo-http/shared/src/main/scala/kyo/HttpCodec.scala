package kyo

import java.util.UUID

trait HttpCodec[A]:
    def parse(s: String): A
    def serialize(a: A): String

object HttpCodec:
    def apply[A](parse: String => A, serialize: A => String): HttpCodec[A] =
        val p = parse; val s = serialize
        new HttpCodec[A]:
            def parse(str: String) = p(str)
            def serialize(a: A)    = s(a)
    end apply

    given HttpCodec[Int]     = HttpCodec(_.toInt, _.toString)
    given HttpCodec[Long]    = HttpCodec(_.toLong, _.toString)
    given HttpCodec[String]  = HttpCodec(identity, identity)
    given HttpCodec[Boolean] = HttpCodec(_.toBoolean, _.toString)
    given HttpCodec[Double]  = HttpCodec(_.toDouble, _.toString)
    given HttpCodec[Float]   = HttpCodec(_.toFloat, _.toString)
    given HttpCodec[UUID]    = HttpCodec(UUID.fromString, _.toString)
end HttpCodec
