package kyo.internal.reflect.tasty

import kyo.*
import kyo.internal.reflect.binary.ByteView
import scala.collection.immutable.IntMap

/** Reads the TASTy Comments section.
  *
  * The Comments section stores per-definition scaladoc strings, indexed by the byte address of the definition node in the AST section.
  *
  * Format (from dotty CommentUnpickler.scala, per TastyReader API):
  * {{{
  * CommentsSection = Entry*
  * Entry           = Addr Utf8 LongInt
  * Addr            = Nat          -- byte address of the definition node in the AST section
  * Utf8            = Nat Byte*    -- length-prefixed UTF-8 string (the scaladoc text)
  * LongInt         = ...          -- span information (source position); not used here, skipped
  * }}}
  *
  * Addr values that are not present in `addrMap` correspond to sub-expression nodes (not definitions); these entries are skipped.
  *
  * Reference: dotty compiler/src/dotty/tools/dotc/core/tasty/CommentUnpickler.scala.
  */
object CommentsUnpickler:

    /** Read the Comments section payload from `view` and return a map from symbol to raw scaladoc text.
      *
      * @param view
      *   ByteView positioned at the start of the Comments section payload. `view.remaining` covers the full section.
      * @param addrMap
      *   Map from TASTy byte address to symbol, as produced by Pass 1 (AstUnpickler.Pass1Result.addrMap). Entries whose address is not in
      *   this map are skipped.
      */
    def read(
        view: ByteView,
        addrMap: IntMap[Reflect.Symbol]
    )(using Frame): Map[Reflect.Symbol, String] < (Sync & Abort[ReflectError]) =
        val result =
            try Right(readSync(view, addrMap))
            catch
                case _: ArrayIndexOutOfBoundsException =>
                    Left(ReflectError.MalformedSection("Comments", "unexpected end of Comments section"))
        result match
            case Right(m)  => Sync.defer(m)
            case Left(err) => Abort.fail(err)
    end read

    private def readSync(view: ByteView, addrMap: IntMap[Reflect.Symbol]): Map[Reflect.Symbol, String] =
        val builder = Map.newBuilder[Reflect.Symbol, String]
        while view.remaining > 0 do
            val addr    = view.readNat()
            val textLen = view.readNat()
            val buf     = new Array[Byte](textLen)
            var i       = 0
            while i < textLen do
                buf(i) = view.readByte()
                i += 1
            end while
            // Skip span (LongInt: signed big-endian base-128 encoding, same loop as Varint.readLongInt but result discarded)
            skipLongInt(view)
            addrMap.get(addr) match
                case Some(sym) =>
                    val text = new String(buf, java.nio.charset.StandardCharsets.UTF_8)
                    builder += (sym -> text)
                case None => () // sub-expression node, not a definition; skip
            end match
        end while
        builder.result()
    end readSync

    /** Skip a signed big-endian base-128 Long (continuation bit is 0x80 CLEAR; stop on 0x80 SET). */
    private def skipLongInt(view: ByteView): Unit =
        var b = view.readByte() & 0xff
        while (b & 0x80) == 0 do
            b = view.readByte() & 0xff
        end while
    end skipLongInt

end CommentsUnpickler
