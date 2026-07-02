package kyo.internal.tasty.symbol

import kyo.*
import kyo.Tasty
import scala.collection.immutable.IntMap

/** Byte slice and decode context for a TASTy symbol body. Stored in DecodeContext.bodyStore keyed by SymbolId.
  *
  * All symbols with a TASTy body (DEFDEF, VALDEF, class TYPEDEF with a non-trivial template) have an entry in the body store.
  * Java symbols, Package symbols, and abstract type stubs have no entry.
  *
  * @param bodyStart
  *   Absolute byte offset into sectionBytes where this symbol's body payload begins.
  * @param bodyEnd
  *   Absolute byte offset into sectionBytes where this symbol's body payload ends.
  * @param sectionBytes
  *   The raw AST section bytes for this file. Shared (not copied) across all symbols from the same file.
  * @param names
  *   The name table for this file, as decoded by NameUnpickler. Shared across all symbols from the same file.
  * @param sectionOffset
  *   Absolute byte offset where the AST section starts in the original TASTy file.
  * @param addrMap
  *   Maps TASTy byte address to SymbolId for IDENT/SELECT tree references during lazy body decode.
  */
final private[kyo] case class SymbolBody(
    bodyStart: Int,
    bodyEnd: Int,
    sectionBytes: Span[Byte],
    names: Span[Tasty.Name],
    sectionOffset: Int,
    private[kyo] val addrMap: IntMap[Tasty.SymbolId],
    // pickleId: the index of the .tasty pickle this body came from. Distinguishes bodies that
    // share one SOURCEFILE but live in different pickles (two top-level declarations of one .scala
    // file compile to two pickles), so the occurrence scanner joins a body only to its own pickle's
    // Positions data. Deterministic (the FileResult merge order). Not part of equals/hashCode:
    // sectionBytes/sectionOffset/names already distinguish pickles; pickleId is derived metadata.
    pickleId: Int
):
    override def equals(other: Any): Boolean = other match
        case that: SymbolBody =>
            bodyStart == that.bodyStart &&
            bodyEnd == that.bodyEnd &&
            sectionOffset == that.sectionOffset &&
            addrMap == that.addrMap &&
            sectionBytes.is(that.sectionBytes) &&
            namesEqual(names, that.names)
        // Carve-out: Any is open; exhaustive enumeration not possible
        case _ => false

    private def namesEqual(a: Span[Tasty.Name], b: Span[Tasty.Name]): Boolean =
        import Tasty.Name.asString
        val len = a.size
        if len != b.size then false
        else
            var i  = 0
            var ok = true
            while i < len && ok do
                if a(i).asString != b(i).asString then ok = false
                i += 1
            ok
        end if
    end namesEqual

    override def hashCode(): Int =
        import Tasty.Name.asString
        var h = 1
        h = 31 * h + bodyStart
        h = 31 * h + bodyEnd
        h = 31 * h + sectionOffset
        h = 31 * h + addrMap.hashCode
        h = 31 * h + sectionBytes.hash
        val namesLen = names.size
        var i        = 0
        while i < namesLen do
            h = 31 * h + names(i).asString.hashCode
            i += 1
        h
    end hashCode

    override def toString: String =
        s"SymbolBody(bodyStart=$bodyStart, bodyEnd=$bodyEnd, sectionBytes=len=${sectionBytes.size}, " +
            s"names=[${names.size} entries], sectionOffset=$sectionOffset, addrMap=${addrMap.size} entries)"
end SymbolBody
