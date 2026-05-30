package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView

/** File-level attributes decoded from the optional TASTy `Attributes` section (Scala 3.3+).
  *
  * If the file has no `Attributes` section, use `FileAttributes.default` (all flags false, sourceFile absent).
  */
final case class FileAttributes(
    explicitNulls: Boolean,
    captureChecked: Boolean,
    isJava: Boolean,
    isOutline: Boolean,
    scala2StandardLibrary: Boolean,
    sourceFile: Maybe[String]
) derives CanEqual

object FileAttributes:
    val default: FileAttributes = FileAttributes(
        explicitNulls = false,
        captureChecked = false,
        isJava = false,
        isOutline = false,
        scala2StandardLibrary = false,
        sourceFile = Absent
    )
end FileAttributes

/** Reads the TASTy `Attributes` section (Scala 3.3+).
  *
  * Attribute grammar (verbatim from dotty TastyFormat.scala, lines 282-301):
  * {{{
  * Attribute = SCALA2STANDARDLIBRARYattr    -- tag=1, no payload
  *           | EXPLICITNULLSattr            -- tag=2, no payload
  *           | CAPTURECHECKEDattr           -- tag=3, no payload
  *           | WITHPUREFUNSattr             -- tag=4, no payload (not surfaced)
  *           | JAVAattr                     -- tag=5, no payload
  *           | OUTLINEattr                  -- tag=6, no payload
  *           | SOURCEFILEattr Utf8Ref       -- tag=129, payload=Nat (0-based NameRef)
  * }}}
  *
  * Category 1 (tags 1-32): boolean flags, no payload. Category 3 (tags 129-160): one Nat payload (Utf8Ref). Tags outside these categories
  * produce `MalformedSection`.
  *
  * Unknown tags within defined categories are skipped for forward compatibility.
  */
object AttributeUnpickler:

    /** Sentinel exception used internally to signal a malformed unknown tag (not an AIOOBE). */
    private class UnknownTagException(val tag: Int, val pos: Int) extends Exception

    /** Read the Attributes section from `view`.
      *
      * `view` must be positioned at the start of the section payload (after the section length has been consumed by the caller). Reads
      * until `view.remaining == 0`.
      *
      * `names` is the 0-based name table array produced by `NameUnpickler.read`. NameRef values in the section are 0-based indices into
      * this array.
      */
    def read(view: ByteView, names: Array[Tasty.Name])(using Frame): FileAttributes < (Sync & Abort[TastyError]) =
        val result =
            try Right(readSync(view, names))
            catch
                case _: ArrayIndexOutOfBoundsException =>
                    // no cursor: exception does not carry a byte offset
                    Left(TastyError.MalformedSection("Attributes", "unexpected end of attributes section", 0L))
                case e: UnknownTagException =>
                    Left(TastyError.MalformedSection("Attributes", s"Unknown attribute tag ${e.tag} at position ${e.pos}", e.pos.toLong))
        result match
            case Right(fa) => Sync.defer(fa)
            case Left(err) => Abort.fail(err)
    end read

    private def readSync(view: ByteView, names: Array[Tasty.Name]): FileAttributes =
        // Unsafe: Name.asString requires AllowUnsafe; embraced here in the decode-pass context (§839 case 3).
        import AllowUnsafe.embrace.danger
        var explicitNulls             = false
        var captureChecked            = false
        var isJava                    = false
        var isOutline                 = false
        var scala2StandardLibrary     = false
        var sourceFile: Maybe[String] = Absent
        while view.remaining > 0 do
            val tag = view.readByte() & 0xff
            tag match
                case TastyFormat.SCALA2STANDARDLIBRARYattr => scala2StandardLibrary = true
                case TastyFormat.EXPLICITNULLSattr         => explicitNulls = true
                case TastyFormat.CAPTURECHECKEDattr        => captureChecked = true
                case TastyFormat.WITHPUREFUNSattr          => () // not surfaced, skip
                case TastyFormat.JAVAattr                  => isJava = true
                case TastyFormat.OUTLINEattr               => isOutline = true
                case TastyFormat.SOURCEFILEattr =>
                    val nameRef = view.readNat() // 0-based NameRef
                    sourceFile = Present(names(nameRef).asString)
                case unknown =>
                    // Forward-compatible: skip known-category unknowns, fail on truly unknown.
                    if TastyFormat.isBooleanAttrTag(unknown) then ()                       // no payload
                    else if TastyFormat.isStringAttrTag(unknown) then view.readNat(): Unit // skip Utf8Ref
                    else throw new UnknownTagException(unknown, view.positionInt)
            end match
        end while
        FileAttributes(explicitNulls, captureChecked, isJava, isOutline, scala2StandardLibrary, sourceFile)
    end readSync

end AttributeUnpickler
