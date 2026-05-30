package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.binary.Utf8
import kyo.internal.tasty.symbol.Interner

/** Reads the TASTy name table section into an `Array[Tasty.Name]`.
  *
  * Format (verbatim from dotty TastyFormat.scala and TastyUnpickler.scala):
  *
  * The name table is byte-count-delimited. After the UUID in the file header, a `nameTable_Length` Nat gives the total BYTE COUNT of the
  * name table. The unpickler reads entries in a loop until `position >= nameTableEnd` (not until a fixed count of entries).
  *
  * Name-record grammar:
  * {{{
  * Name  = UTF8              Utf8                                                      -- tag=1
  *         QUALIFIED         Length qualified_NameRef selector_NameRef                 -- tag=2
  *         EXPANDED          Length qualified_NameRef selector_NameRef                 -- tag=3
  *         EXPANDPREFIX      Length qualified_NameRef selector_NameRef                 -- tag=4
  *         UNIQUE            Length separator_NameRef uniqid_Nat underlying_NameRef?   -- tag=10
  *         DEFAULTGETTER     Length underlying_NameRef index_Nat                       -- tag=11
  *         SUPERACCESSOR     Length underlying_NameRef                                 -- tag=20
  *         INLINEACCESSOR    Length underlying_NameRef                                 -- tag=21
  *         OBJECTCLASS       Length underlying_NameRef                                 -- tag=23
  *         BODYRETAINER      Length underlying_NameRef                                 -- tag=22
  *         SIGNED            Length original_NameRef resultSig_NameRef ParamSig*       -- tag=63
  *         TARGETSIGNED      Length original_NameRef target_NameRef resultSig_NameRef ParamSig* -- tag=62
  * }}}
  *
  * NameRef fields are 0-based indices into the already-decoded names array. `readEnd()` reads the payload byte count and returns
  * `cursor + count` (absolute end position).
  *
  * Produced strings are interned via the supplied `Interner`, so duplicate names within a file share the same `Tasty.Name` reference.
  */
object NameUnpickler:

    /** Read the name table from `view`.
      *
      * `view` must be positioned at the start of the name table (immediately after the file header UUID). Reads `nameTableByteCount` (a
      * Nat), then entries until the cursor reaches `nameTableStart + nameTableByteCount`.
      *
      * Returns an `Array[Tasty.Name]` indexed 0-based matching the NameRef convention used by the section table.
      */
    def read(view: ByteView, interner: Interner)(using Frame): Array[Tasty.Name] < (Sync & Abort[TastyError]) =
        val result =
            try Right(readUnsafe(view, interner))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    val reason = if ex.getMessage != null then ex.getMessage else "unexpected end of name table"
                    Left(TastyError.MalformedSection("Names", reason, view.position))
                case ex: java.lang.Error
                    if ex.getCause != null && ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] =>
                    Left(TastyError.MalformedSection("Names", "unexpected end of name table", view.position))
        result match
            case Right(names) => Sync.defer(names)
            case Left(err)    => Abort.fail(err)
    end read

    private def readUnsafe(view: ByteView, interner: Interner): Array[Tasty.Name] =
        // Unsafe: Name.asString requires AllowUnsafe; embraced here in the decode-pass context (§839 case 3).
        import AllowUnsafe.embrace.danger
        val nameTableByteCount = view.readNat()
        val nameTableEnd       = view.position + nameTableByteCount
        val buf                = new scala.collection.mutable.ArrayBuffer[Tasty.Name]()
        while view.position < nameTableEnd do
            val tag = view.readByte() & 0xff
            tag match
                case TastyFormat.NameTags.UTF8 =>
                    val length = view.readNat()
                    // Read `length` raw bytes from the view into a local buffer.
                    val nameBytes = readBytes(view, length)
                    val entry     = interner.intern(nameBytes, 0, length)
                    buf += Tasty.Name.wrap(entry)
                case TastyFormat.NameTags.QUALIFIED =>
                    val end      = view.readEnd()
                    val prefix   = view.readNat()
                    val selector = view.readNat()
                    view.goto(end)
                    checkRef(prefix, buf, "QUALIFIED prefix")
                    checkRef(selector, buf, "QUALIFIED selector")
                    val s = buf(prefix).asString + "." + buf(selector).asString
                    buf += internString(interner, s)
                case TastyFormat.NameTags.EXPANDED =>
                    val end      = view.readEnd()
                    val prefix   = view.readNat()
                    val selector = view.readNat()
                    view.goto(end)
                    checkRef(prefix, buf, "EXPANDED prefix")
                    checkRef(selector, buf, "EXPANDED selector")
                    val s = buf(prefix).asString + "$$" + buf(selector).asString
                    buf += internString(interner, s)
                case TastyFormat.NameTags.EXPANDPREFIX =>
                    val end      = view.readEnd()
                    val prefix   = view.readNat()
                    val selector = view.readNat()
                    view.goto(end)
                    checkRef(prefix, buf, "EXPANDPREFIX prefix")
                    checkRef(selector, buf, "EXPANDPREFIX selector")
                    val s = buf(prefix).asString + "$" + buf(selector).asString
                    buf += internString(interner, s)
                case TastyFormat.NameTags.UNIQUE =>
                    val end       = view.readEnd()
                    val separator = view.readNat()
                    val uniqid    = view.readNat()
                    val underlying =
                        if view.position < end then Some(view.readNat()) else None
                    view.goto(end)
                    checkRef(separator, buf, "UNIQUE separator")
                    underlying.foreach(ref => checkRef(ref, buf, "UNIQUE underlying"))
                    val sep = buf(separator).asString
                    val s = underlying match
                        case Some(ref) => buf(ref).asString + sep + uniqid.toString
                        case None      => sep + uniqid.toString
                    buf += internString(interner, s)
                case TastyFormat.NameTags.DEFAULTGETTER =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    val index      = view.readNat()
                    view.goto(end)
                    checkRef(underlying, buf, "DEFAULTGETTER underlying")
                    val s = buf(underlying).asString + "$default$" + index.toString
                    buf += internString(interner, s)
                case TastyFormat.NameTags.SUPERACCESSOR =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    view.goto(end)
                    checkRef(underlying, buf, "SUPERACCESSOR underlying")
                    val s = "super$" + buf(underlying).asString
                    buf += internString(interner, s)
                case TastyFormat.NameTags.INLINEACCESSOR =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    view.goto(end)
                    checkRef(underlying, buf, "INLINEACCESSOR underlying")
                    val s = "inline$" + buf(underlying).asString
                    buf += internString(interner, s)
                case TastyFormat.NameTags.OBJECTCLASS =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    view.goto(end)
                    checkRef(underlying, buf, "OBJECTCLASS underlying")
                    val s = buf(underlying).asString + "$"
                    buf += internString(interner, s)
                case TastyFormat.NameTags.BODYRETAINER =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    view.goto(end)
                    checkRef(underlying, buf, "BODYRETAINER underlying")
                    val s = buf(underlying).asString + "$retainedBody"
                    buf += internString(interner, s)
                case TastyFormat.NameTags.SIGNED =>
                    val end       = view.readEnd()
                    val original  = view.readNat()
                    val resultSig = view.readNat()
                    val paramSigs = new scala.collection.mutable.ArrayBuffer[String]()
                    while view.position < end do
                        val ps = view.readInt()
                        if ps < 0 then paramSigs += ("-" + (-ps).toString)
                        else if ps > 0 then
                            checkRef(ps, buf, "SIGNED paramSig")
                            paramSigs += buf(ps).asString
                        else
                            view.goto(end)
                            throw new ArrayIndexOutOfBoundsException("SIGNED paramSig == 0: invalid")
                        end if
                    end while
                    view.goto(end)
                    checkRef(original, buf, "SIGNED original")
                    checkRef(resultSig, buf, "SIGNED resultSig")
                    val s = buf(original).asString + ":" + buf(resultSig).asString + "(" + paramSigs.mkString(",") + ")"
                    buf += internString(interner, s)
                case TastyFormat.NameTags.TARGETSIGNED =>
                    val end       = view.readEnd()
                    val original  = view.readNat()
                    val target    = view.readNat()
                    val resultSig = view.readNat()
                    val paramSigs = new scala.collection.mutable.ArrayBuffer[String]()
                    while view.position < end do
                        val ps = view.readInt()
                        if ps < 0 then paramSigs += ("-" + (-ps).toString)
                        else if ps > 0 then
                            checkRef(ps, buf, "TARGETSIGNED paramSig")
                            paramSigs += buf(ps).asString
                        else
                            view.goto(end)
                            throw new ArrayIndexOutOfBoundsException("TARGETSIGNED paramSig == 0: invalid")
                        end if
                    end while
                    view.goto(end)
                    checkRef(original, buf, "TARGETSIGNED original")
                    checkRef(target, buf, "TARGETSIGNED target")
                    checkRef(resultSig, buf, "TARGETSIGNED resultSig")
                    val s = buf(original).asString + "[" + buf(target).asString + "]:" + buf(resultSig).asString + "(" + paramSigs.mkString(
                        ","
                    ) + ")"
                    buf += internString(interner, s)
                case unknown =>
                    throw new ArrayIndexOutOfBoundsException(
                        s"Unrecognized name tag $unknown at position ${view.position - 1}"
                    )
            end match
        end while
        buf.toArray
    end readUnsafe

    /** Check that `ref` is a valid index into `buf` and throw ArrayIndexOutOfBoundsException if not. */
    private def checkRef(ref: Int, buf: scala.collection.mutable.ArrayBuffer[Tasty.Name], role: String): Unit =
        if ref < 0 || ref >= buf.length then
            throw new ArrayIndexOutOfBoundsException(
                s"$role nameRef out of range: ref=$ref tableSize=${buf.length}"
            )
    end checkRef

    /** Read `length` bytes from `view` into a fresh `Array[Byte]`. */
    private def readBytes(view: ByteView, length: Int)(using AllowUnsafe): Array[Byte] =
        val arr = new Array[Byte](length)
        var i   = 0
        while i < length do
            arr(i) = view.readByte()
            i += 1
        arr
    end readBytes

    /** Intern a `String` by encoding to UTF-8 and passing to the interner. */
    private def internString(interner: Interner, s: String)(using AllowUnsafe): Tasty.Name =
        val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        Tasty.Name.wrap(interner.intern(bytes, 0, bytes.length))
    end internString

end NameUnpickler
