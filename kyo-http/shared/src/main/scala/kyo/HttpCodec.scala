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

    /** What to publish for values of this type, when the codec knows more than
      * the Scala type does.
      *
      * `Absent` means "infer it": the OpenAPI generator probes the codec with
      * sample values to guess a JSON type, which is all it can do for
      * `HttpCodec[String]`. A codec that accepts only a subset of strings —
      * a hash, an address, an enum — can say so here, and the constraint reaches
      * the document instead of living only in prose.
      *
      * Prefer building the codec with [[HttpCodec.pattern]] over setting this by
      * hand, so the expression that rejects a value is the same one that
      * describes it.
      */
    def schema: Maybe[HttpOpenApi.SchemaObject] = Absent

end HttpCodec

object HttpCodec:

    @nowarn("msg=anonymous")
    inline def apply[A](inline enc: A => String, inline dec: String => A): HttpCodec[A] =
        val e = enc; val d = dec
        new HttpCodec[A]:
            def encode(value: A)                          = e(value)
            def decode(raw: String): Result[Throwable, A] = Result.catching[Throwable](d(raw))
        end new
    end apply

    @nowarn("msg=anonymous")
    inline def apply[A](inline enc: A => String, inline dec: String => A, published: HttpOpenApi.SchemaObject): HttpCodec[A] =
        val e = enc; val d = dec; val s = published
        new HttpCodec[A]:
            def encode(value: A)                          = e(value)
            def decode(raw: String): Result[Throwable, A] = Result.catching[Throwable](d(raw))
            override def schema                           = Present(s)
        end new
    end apply

    /** A codec whose accepted values are exactly those matching `regex`.
      *
      * The expression is applied twice, deliberately: it rejects a value that
      * does not match, and it is published as the parameter's `pattern`. One
      * declaration, so the document cannot describe a rule the server does not
      * enforce.
      *
      * A non-matching value fails `decode`, which is what lets a caller be told
      * the shape was wrong rather than having the value carried into a lookup
      * that can only come back empty.
      *
      * {{{
      * given HttpCodec[BlockHash] =
      *     HttpCodec.pattern("^0x[0-9a-fA-F]{64}\$")(_.value, BlockHash(_), "32-byte block hash")
      * }}}
      */
    @nowarn("msg=anonymous")
    inline def pattern[A](regex: String)(
        inline enc: A => String,
        inline dec: String => A,
        description: String = ""
    ): HttpCodec[A] =
        val e        = enc; val d = dec; val r = regex; val desc = description
        val compiled = r.r
        new HttpCodec[A]:
            def encode(value: A) = e(value)
            def decode(raw: String): Result[Throwable, A] =
                if compiled.matches(raw) then Result.catching[Throwable](d(raw))
                else Result.fail(new IllegalArgumentException(s"expected a value matching $r, got: $raw"))
            override def schema =
                Present(HttpOpenApi.SchemaObject.stringMatching(r, if desc.isEmpty then Absent else Present(desc)))
        end new
    end pattern

    extension [A](self: HttpCodec[A])
        /** Attach a published schema to an existing codec, including a built-in
          * one. Decoding is unchanged — this only affects the document, so use it
          * for descriptions and formats rather than for constraints that ought to
          * be enforced.
          */
        def withSchema(published: HttpOpenApi.SchemaObject): HttpCodec[A] =
            new HttpCodec[A]:
                def encode(value: A)                          = self.encode(value)
                def decode(raw: String): Result[Throwable, A] = self.decode(raw)
                override def schema                           = Present(published)
    end extension

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
