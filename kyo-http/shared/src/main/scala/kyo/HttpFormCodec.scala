package kyo

import scala.annotation.tailrec
import scala.compiletime.*
import scala.deriving.Mirror

abstract class HttpFormCodec[A]:
    def encode(a: A): String
    def decode(s: String): A

object HttpFormCodec:

    inline def derived[A <: Product](using m: Mirror.ProductOf[A]): HttpFormCodec[A] =
        val fieldCodecs = summonFieldCodecs[m.MirroredElemTypes]
        val fieldNames  = collectFieldNames[m.MirroredElemLabels]
        ProductFormCodec[A](m, fieldNames, fieldCodecs)
    end derived

    private[kyo] class ProductFormCodec[A <: Product](
        mirror: Mirror.ProductOf[A],
        fieldNames: Array[String],
        fieldCodecs: Array[HttpCodec[Any]]
    ) extends HttpFormCodec[A]:

        def decode(s: String): A =
            val values = new Array[Any](fieldNames.length)
            val map    = new java.util.HashMap[String, String](fieldNames.length * 2)
            if s.nonEmpty then
                val pairs = s.split('&')
                @tailrec def parsePairs(i: Int): Unit =
                    if i < pairs.length then
                        val pair  = pairs(i)
                        val eqIdx = pair.indexOf('=')
                        if eqIdx >= 0 then
                            val key   = java.net.URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8")
                            val value = java.net.URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8")
                            kyo.discard(map.put(key, value))
                        end if
                        parsePairs(i + 1)
                parsePairs(0)
            end if
            @tailrec def fillValues(j: Int): Unit =
                if j < fieldNames.length then
                    val raw = map.get(fieldNames(j))
                    if raw == null then
                        throw new IllegalArgumentException(s"Missing required form field: ${fieldNames(j)}")
                    values(j) = fieldCodecs(j).decode(raw)
                    fillValues(j + 1)
            fillValues(0)
            mirror.fromProduct(Tuple.fromArray(values))
        end decode

        def encode(a: A): String =
            val sb = new java.lang.StringBuilder()
            @tailrec def loop(i: Int): Unit =
                if i < fieldNames.length then
                    if i > 0 then kyo.discard(sb.append('&'))
                    sb.append(java.net.URLEncoder.encode(fieldNames(i), "UTF-8"))
                    sb.append('=')
                    sb.append(java.net.URLEncoder.encode(fieldCodecs(i).encode(a.productElement(i)), "UTF-8"))
                    loop(i + 1)
            loop(0)
            sb.toString
        end encode
    end ProductFormCodec

    private inline def summonFieldCodecs[T <: Tuple]: Array[HttpCodec[Any]] =
        inline erasedValue[T] match
            case _: EmptyTuple => Array.empty[HttpCodec[Any]]
            case _: (h *: t) =>
                summonInline[HttpCodec[h]].asInstanceOf[HttpCodec[Any]] +: summonFieldCodecs[t]

    private inline def collectFieldNames[T <: Tuple]: Array[String] =
        inline erasedValue[T] match
            case _: EmptyTuple => Array.empty[String]
            case _: (h *: t) =>
                constValue[h & String] +: collectFieldNames[t]

end HttpFormCodec
