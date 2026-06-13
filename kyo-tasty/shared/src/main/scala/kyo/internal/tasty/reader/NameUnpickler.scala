package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.binary.Utf8

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
  * Produced strings are stored directly as `Tasty.Name` values (opaque `String`).
  */
object NameUnpickler:

    /** Read the name table from `view`.
      *
      * `view` must be positioned at the start of the name table (immediately after the file header UUID). Reads `nameTableByteCount` (a
      * Nat), then entries until the cursor reaches `nameTableStart + nameTableByteCount`.
      *
      * Returns an `Array[Tasty.Name]` indexed 0-based matching the NameRef convention used by the section table.
      */
    def read(view: ByteView)(using Frame): Array[Tasty.Name] < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer {
            try Right(readUnsafe(view))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    // Translate raw JVM array-index message to a typed, reader-comprehensible reason.
                    val reason =
                        if ex.getMessage != null then
                            val msg   = ex.getMessage
                            val index = msg.stripPrefix("Array index out of range: ")
                            if index != msg then s"name table index $index out of range"
                            else s"name table corrupted: $msg"
                        else "name table: unexpected end of data"
                    Left(TastyError.MalformedSection("Names", reason, view.position))
                case ex: java.lang.Error
                    if ex.getCause != null && ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] =>
                    Left(TastyError.MalformedSection("Names", "name table: unexpected end of data", view.position))
        }.map {
            case Right(names) => names
            case Left(err)    => Abort.fail(err)
        }
    end read

    /** Result-returning sibling of `read` for use inside an unsafe-tier Result-flow.
      *
      * Identical exception-to-error translation as `read`, but returns `Result[TastyError, Array[Tasty.Name]]`
      * synchronously and skips the outer `Sync.Unsafe.defer` wrapping. Callers compose this with other
      * Result-returning siblings in a for-yield over `Result`.
      */
    private[kyo] def readUnsafeResult(view: ByteView)(using Frame, AllowUnsafe): Result[TastyError, Array[Tasty.Name]] =
        try Result.Success(readUnsafe(view))
        catch
            case ex: ArrayIndexOutOfBoundsException =>
                val reason =
                    if ex.getMessage != null then
                        val msg   = ex.getMessage
                        val index = msg.stripPrefix("Array index out of range: ")
                        if index != msg then s"name table index $index out of range"
                        else s"name table corrupted: $msg"
                    else "name table: unexpected end of data"
                Result.Failure(TastyError.MalformedSection("Names", reason, view.position))
            case ex: java.lang.Error
                if ex.getCause != null && ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] =>
                Result.Failure(TastyError.MalformedSection("Names", "name table: unexpected end of data", view.position))
    end readUnsafeResult

    private def readUnsafe(view: ByteView)(using AllowUnsafe): Array[Tasty.Name] =
        val nameTableByteCount = view.readNat()
        val nameTableEnd       = view.position + nameTableByteCount
        val accumulator        = new scala.collection.mutable.ArrayBuffer[Tasty.Name]()
        while view.position < nameTableEnd do
            val tag = view.readByte() & 0xff
            tag match
                case TastyFormat.NameTags.UTF8 =>
                    val length    = view.readNat()
                    val nameBytes = readBytes(view, length)
                    accumulator += Tasty.Name(Utf8.decode(nameBytes, 0, length))
                case TastyFormat.NameTags.QUALIFIED =>
                    val end      = view.readEnd()
                    val prefix   = view.readNat()
                    val selector = view.readNat()
                    view.goto(end)
                    checkRef(prefix, accumulator, "QUALIFIED prefix")
                    checkRef(selector, accumulator, "QUALIFIED selector")
                    accumulator += Tasty.Name(accumulator(prefix).asString + "." + accumulator(selector).asString)
                case TastyFormat.NameTags.EXPANDED =>
                    val end      = view.readEnd()
                    val prefix   = view.readNat()
                    val selector = view.readNat()
                    view.goto(end)
                    checkRef(prefix, accumulator, "EXPANDED prefix")
                    checkRef(selector, accumulator, "EXPANDED selector")
                    accumulator += Tasty.Name(accumulator(prefix).asString + "$$" + accumulator(selector).asString)
                case TastyFormat.NameTags.EXPANDPREFIX =>
                    val end      = view.readEnd()
                    val prefix   = view.readNat()
                    val selector = view.readNat()
                    view.goto(end)
                    checkRef(prefix, accumulator, "EXPANDPREFIX prefix")
                    checkRef(selector, accumulator, "EXPANDPREFIX selector")
                    accumulator += Tasty.Name(accumulator(prefix).asString + "$" + accumulator(selector).asString)
                case TastyFormat.NameTags.UNIQUE =>
                    val end       = view.readEnd()
                    val separator = view.readNat()
                    val uniqid    = view.readNat()
                    val underlying =
                        if view.position < end then Some(view.readNat()) else None
                    view.goto(end)
                    checkRef(separator, accumulator, "UNIQUE separator")
                    underlying.foreach(ref => checkRef(ref, accumulator, "UNIQUE underlying"))
                    val sep = accumulator(separator).asString
                    val s = underlying match
                        case Some(ref) => accumulator(ref).asString + sep + uniqid.toString
                        case None      => sep + uniqid.toString
                    accumulator += Tasty.Name(s)
                case TastyFormat.NameTags.DEFAULTGETTER =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    val index      = view.readNat()
                    view.goto(end)
                    checkRef(underlying, accumulator, "DEFAULTGETTER underlying")
                    accumulator += Tasty.Name(accumulator(underlying).asString + "$default$" + index.toString)
                case TastyFormat.NameTags.SUPERACCESSOR =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    view.goto(end)
                    checkRef(underlying, accumulator, "SUPERACCESSOR underlying")
                    accumulator += Tasty.Name("super$" + accumulator(underlying).asString)
                case TastyFormat.NameTags.INLINEACCESSOR =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    view.goto(end)
                    checkRef(underlying, accumulator, "INLINEACCESSOR underlying")
                    accumulator += Tasty.Name("inline$" + accumulator(underlying).asString)
                case TastyFormat.NameTags.OBJECTCLASS =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    view.goto(end)
                    checkRef(underlying, accumulator, "OBJECTCLASS underlying")
                    accumulator += Tasty.Name(accumulator(underlying).asString + "$")
                case TastyFormat.NameTags.BODYRETAINER =>
                    val end        = view.readEnd()
                    val underlying = view.readNat()
                    view.goto(end)
                    checkRef(underlying, accumulator, "BODYRETAINER underlying")
                    accumulator += Tasty.Name(accumulator(underlying).asString + "$retainedBody")
                case TastyFormat.NameTags.SIGNED =>
                    val end       = view.readEnd()
                    val original  = view.readNat()
                    val resultSig = view.readNat()
                    val paramSigs = new scala.collection.mutable.ArrayBuffer[String]()
                    while view.position < end do
                        val ps = view.readInt()
                        if ps < 0 then paramSigs += ("-" + (-ps).toString)
                        else if ps > 0 then
                            checkRef(ps, accumulator, "SIGNED paramSig")
                            paramSigs += accumulator(ps).asString
                        else
                            view.goto(end)
                            throw new ArrayIndexOutOfBoundsException("SIGNED paramSig == 0: invalid")
                        end if
                    end while
                    view.goto(end)
                    checkRef(original, accumulator, "SIGNED original")
                    checkRef(resultSig, accumulator, "SIGNED resultSig")
                    accumulator += Tasty.Name(
                        accumulator(original).asString + ":" + accumulator(resultSig).asString + "(" + paramSigs.mkString(",") + ")"
                    )
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
                            checkRef(ps, accumulator, "TARGETSIGNED paramSig")
                            paramSigs += accumulator(ps).asString
                        else
                            view.goto(end)
                            throw new ArrayIndexOutOfBoundsException("TARGETSIGNED paramSig == 0: invalid")
                        end if
                    end while
                    view.goto(end)
                    checkRef(original, accumulator, "TARGETSIGNED original")
                    checkRef(target, accumulator, "TARGETSIGNED target")
                    checkRef(resultSig, accumulator, "TARGETSIGNED resultSig")
                    accumulator += Tasty.Name(
                        accumulator(original).asString + "[" + accumulator(target).asString + "]:" + accumulator(
                            resultSig
                        ).asString + "(" + paramSigs.mkString(
                            ","
                        ) + ")"
                    )
                case unknown =>
                    throw new ArrayIndexOutOfBoundsException(
                        s"Unrecognized name tag $unknown at position ${view.position - 1}"
                    )
            end match
        end while
        accumulator.toArray
    end readUnsafe

    /** Check that `ref` is a valid index into `accumulator` and throw ArrayIndexOutOfBoundsException if not. */
    private def checkRef(ref: Int, accumulator: scala.collection.mutable.ArrayBuffer[Tasty.Name], role: String): Unit =
        if ref < 0 || ref >= accumulator.length then
            throw new ArrayIndexOutOfBoundsException(
                s"$role nameRef out of range: ref=$ref tableSize=${accumulator.length}"
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

end NameUnpickler
