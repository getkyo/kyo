package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView

/** An index of TASTy section headers, mapping section name to byte range within the file.
  *
  * The section table immediately follows the name table in a TASTy file. Each entry contains a 0-based NameRef into the name table, a byte
  * count for the section payload, and the payload bytes.
  *
  * Format (verbatim from TastyFormat.scala):
  * {{{
  * Section = NameRef Length Bytes
  * }}}
  *
  * NameRefs in the section table are 0-based indices into the `Array[Tasty.Name]` produced by `NameUnpickler`.
  */
final class SectionIndex private (private val sections: Map[String, (Int, Int)]):

    /** Return `Present((offset, length))` for the named section, or `Absent` if not found. */
    def get(name: String): Maybe[(Int, Int)] =
        sections.get(name) match
            case Some(v) => Present(v)
            case None    => Absent

end SectionIndex

object SectionIndex:

    /** Read the section table from `view`, resolving section names via the already-decoded `names` array.
      *
      * `view` must be positioned immediately after the name table (at the first byte of the section table). Reads until
      * `view.remaining == 0`.
      *
      * `names` is the 0-based array produced by `NameUnpickler.read`. Section NameRefs are 0-based indices into this array.
      */
    def read(view: ByteView, names: Array[Tasty.Name])(using Frame): SectionIndex < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer:
            try Right(readSync(view, names))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    val reason = if ex.getMessage != null then ex.getMessage else "unexpected end while reading section headers"
                    Left(TastyError.MalformedSection("SectionIndex", reason, view.position))
        .map:
            case Right(idx) => idx
            case Left(err)  => Abort.fail(err)
    end read

    private def readSync(view: ByteView, names: Array[Tasty.Name])(using AllowUnsafe): SectionIndex =
        val builder = Map.newBuilder[String, (Int, Int)]
        while view.remaining > 0 do
            val nameRef    = view.readNat()   // 0-based index into names
            val sectionLen = view.readNat()   // byte count of payload
            val offset     = view.positionInt // payload starts here
            if nameRef < 0 || nameRef >= names.length then
                throw new ArrayIndexOutOfBoundsException(
                    s"SectionIndex: nameRef=$nameRef out of range (names.length=${names.length}) at byte ${view.position}"
                )
            end if
            if sectionLen < 0 then
                throw new ArrayIndexOutOfBoundsException(
                    s"SectionIndex: negative section length $sectionLen at byte ${view.position}"
                )
            end if
            val name = names(nameRef).asString
            builder += (name -> (offset, sectionLen))
            view.goto(offset + sectionLen) // skip payload
        end while
        new SectionIndex(builder.result())
    end readSync

end SectionIndex
