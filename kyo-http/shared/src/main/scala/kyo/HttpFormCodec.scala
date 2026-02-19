package kyo

import scala.compiletime.*
import scala.deriving.Mirror

/** Codec for encoding/decoding case classes as URL-encoded form bodies (`key=value&key2=value2`).
  *
  * Each field of the case class must have an `HttpParamCodec` instance for its individual value parsing/serialization. Only flat case
  * classes with primitive-codec fields are supported — nested objects and collections will fail at compile time.
  *
  * Use with `HttpRoute.RequestDef.bodyForm[A]`:
  * {{{
  * case class LoginForm(username: String, password: String) derives HttpFormCodec
  *
  * val route = HttpRoute.post("login")
  *     .request(_.bodyForm[LoginForm])
  *     .response(_.bodyText)
  * }}}
  *
  * @tparam A
  *   the case class type to encode/decode
  */
trait HttpFormCodec[A]:
    def parse(s: String): A
    def serialize(a: A): String

object HttpFormCodec:

    inline def derived[A <: Product](using m: Mirror.ProductOf[A]): HttpFormCodec[A] =
        val fieldCodecs = summonFieldCodecs[m.MirroredElemTypes]
        val fieldNames  = collectFieldNames[m.MirroredElemLabels]
        ProductFormCodec[A](m, fieldNames, fieldCodecs)
    end derived

    private[kyo] class ProductFormCodec[A <: Product](
        mirror: Mirror.ProductOf[A],
        fieldNames: Array[String],
        fieldCodecs: Array[HttpParamCodec[Any]]
    ) extends HttpFormCodec[A]:

        def parse(s: String): A =
            val values = new Array[Any](fieldNames.length)
            val map    = new java.util.HashMap[String, String](fieldNames.length * 2)
            if s.nonEmpty then
                val pairs = s.split('&')
                var i     = 0
                while i < pairs.length do
                    val pair  = pairs(i)
                    val eqIdx = pair.indexOf('=')
                    if eqIdx >= 0 then
                        val key   = java.net.URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8")
                        val value = java.net.URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8")
                        discard(map.put(key, value))
                    end if
                    i += 1
                end while
            end if
            var j = 0
            while j < fieldNames.length do
                val raw = map.get(fieldNames(j))
                if raw == null then
                    throw new IllegalArgumentException(s"Missing required form field: ${fieldNames(j)}")
                values(j) = fieldCodecs(j).parse(raw)
                j += 1
            end while
            mirror.fromProduct(Tuple.fromArray(values))
        end parse

        def serialize(a: A): String =
            val sb = new java.lang.StringBuilder()
            var i  = 0
            while i < fieldNames.length do
                if i > 0 then discard(sb.append('&'))
                sb.append(java.net.URLEncoder.encode(fieldNames(i), "UTF-8"))
                sb.append('=')
                sb.append(java.net.URLEncoder.encode(fieldCodecs(i).serialize(a.productElement(i).asInstanceOf[Any]), "UTF-8"))
                i += 1
            end while
            sb.toString
        end serialize
    end ProductFormCodec

    private inline def summonFieldCodecs[T <: Tuple]: Array[HttpParamCodec[Any]] =
        inline erasedValue[T] match
            case _: EmptyTuple => Array.empty[HttpParamCodec[Any]]
            case _: (h *: t) =>
                summonInline[HttpParamCodec[h]].asInstanceOf[HttpParamCodec[Any]] +: summonFieldCodecs[t]

    private inline def collectFieldNames[T <: Tuple]: Array[String] =
        inline erasedValue[T] match
            case _: EmptyTuple => Array.empty[String]
            case _: (h *: t) =>
                constValue[h & String] +: collectFieldNames[t]

end HttpFormCodec
