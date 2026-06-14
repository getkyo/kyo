package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.LoadingSymbol
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
        addrMap: IntMap[LoadingSymbol.Materialising]
    )(using Frame, AllowUnsafe): Result[TastyError, scala.collection.mutable.LongMap[String]] =
        try Result.Success(readSync(view, addrMap))
        catch
            case _: ArrayIndexOutOfBoundsException =>
                Result.Failure(TastyError.MalformedSection("Comments", "unexpected end of Comments section", view.position.toLong))
    end read

    private def readSync(view: ByteView, addrMap: IntMap[LoadingSymbol.Materialising])(using
        AllowUnsafe
    ): scala.collection.mutable.LongMap[String] =
        // Key by symbol.id (primitive Long), not the mutable LoadingSymbol.Materialising case class.
        // Avoids structural-equality fragility when LoadingSymbol.id is mutated post-insertion.
        val builder = scala.collection.mutable.LongMap.empty[String]
        while view.remaining > 0 do
            val address = view.readNat()
            val textLen = view.readNat()
            val buffer  = new Array[Byte](textLen)
            var i       = 0
            while i < textLen do
                buffer(i) = view.readByte()
                i += 1
            end while
            // Skip span (LongInt: signed big-endian base-128 encoding, same loop as Varint.readLongInt but result discarded)
            skipLongInt(view)
            addrMap.get(address) match
                case Some(symbol) =>
                    val text = new String(buffer, java.nio.charset.StandardCharsets.UTF_8)
                    builder(symbol.id.toLong) = text
                case None => () // sub-expression node, not a definition; skip
            end match
        end while
        builder
    end readSync

    /** Skip a signed big-endian base-128 Long (continuation bit is 0x80 CLEAR; stop on 0x80 SET). */
    private def skipLongInt(view: ByteView)(using AllowUnsafe): Unit =
        var b = view.readByte() & 0xff
        while (b & 0x80) == 0 do
            b = view.readByte() & 0xff
        end while
    end skipLongInt

end CommentsUnpickler
