package kyo.internal.tasty.classfile

import kyo.*
import kyo.internal.tasty.binary.ByteView

/** Lazily-decoded JVM constant pool.
  *
  * All accessors return effects: `Abort[TastyError]` for format errors.
  */
final class ConstantPool(
    private val entries: Array[CpEntry | Null],
    val path: String
):

    /** Validate an index and return the entry.
      *
      * Returns a structured error for out-of-range indices, null slots (unread entries), and Long/Double hole slots.
      */
    private def entry(idx: Int)(using Frame): CpEntry < Abort[TastyError] =
        if idx < 1 || idx >= entries.length then
            // no cursor: constant pool entry lookup by index does not have a stream position
            Abort.fail(TastyError.ClassfileFormatError(path, s"Constant pool index $idx out of bounds [1, ${entries.length - 1}]", 0L))
        else
            entries(idx) match
                case null =>
                    // no cursor: constant pool slot validation does not have a stream position
                    Abort.fail(TastyError.ClassfileFormatError(
                        path,
                        s"Constant pool slot $idx is a Long/Double hole (invalid reference)",
                        0L
                    ))
                case e: CpEntry =>
                    if e eq CpEntry.Hole then
                        // no cursor: constant pool slot validation does not have a stream position
                        Abort.fail(TastyError.ClassfileFormatError(
                            path,
                            s"Constant pool slot $idx is the unused second slot of a Long/Double entry (invalid reference)",
                            0L
                        ))
                    else e

    /** Return a short tag name for a CpEntry, used in cross-entry error messages. */
    private def tagName(e: CpEntry): String = e match
        case _: CpEntry.Utf8Lazy           => "Utf8"
        case _: CpEntry.Utf8Decoded        => "Utf8"
        case _: CpEntry.ClassRef           => "ClassRef"
        case _: CpEntry.NameAndType        => "NameAndType"
        case _: CpEntry.Fieldref           => "Fieldref"
        case _: CpEntry.Methodref          => "Methodref"
        case _: CpEntry.InterfaceMethodref => "InterfaceMethodref"
        case _: CpEntry.CpInteger          => "Integer"
        case _: CpEntry.CpFloat            => "Float"
        case _: CpEntry.CpLong             => "Long"
        case _: CpEntry.CpDouble           => "Double"
        case _: CpEntry.StringConst        => "String"
        case _: CpEntry.MethodHandle       => "MethodHandle"
        case _: CpEntry.MethodType         => "MethodType"
        case _: CpEntry.Dynamic            => "Dynamic"
        case _: CpEntry.InvokeDynamic      => "InvokeDynamic"
        case _: CpEntry.CpModule           => "Module"
        case _: CpEntry.CpPackage          => "Package"
        case _                             => "Hole"

    /** Decode UTF-8 constant at `idx`.
      *
      * Fails with a structured error if `pool[idx]` is not a Utf8 entry, including the actual entry kind found.
      */
    def utf8(idx: Int)(using Frame): String < (Sync & Abort[TastyError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case u: CpEntry.Utf8Lazy =>
                    // Sync.Unsafe.defer provides AllowUnsafe for the AtomicRef decode/cache access.
                    Sync.Unsafe.defer:
                        u.decode()
                case CpEntry.Utf8Decoded(v) =>
                    v
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Utf8 at pool[$idx], found ${tagName(other)}", 0L))

    /** Return the binary class name (with '/' separators) for a CONSTANT_Class entry at `idx`.
      *
      * Fails with a structured chain error when `pool[idx]` is not a ClassRef, or when the nameIdx it contains does not point to a Utf8.
      */
    def classRef(idx: Int)(using Frame): String < (Sync & Abort[TastyError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.ClassRef(nameIdx) =>
                    utf8(nameIdx)
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected ClassRef at pool[$idx], found ${tagName(other)}", 0L))

    /** Return an integer constant at `idx`.
      *
      * Fails with a structured error including the actual entry kind found when `pool[idx]` is not an Integer.
      */
    def integer(idx: Int)(using Frame): Int < Abort[TastyError] =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpInteger(value) => value
                // no cursor: constant pool accessor errors do not carry a stream position
                case other =>
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Integer at pool[$idx], found ${tagName(other)}", 0L))

    /** Return a long constant at `idx`.
      *
      * Fails with a structured error including the actual entry kind found when `pool[idx]` is not a Long.
      */
    def long_(idx: Int)(using Frame): Long < Abort[TastyError] =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpLong(value) => value
                // no cursor: constant pool accessor errors do not carry a stream position
                case other => Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Long at pool[$idx], found ${tagName(other)}", 0L))

    /** Return a float constant at `idx`.
      *
      * Fails with a structured error including the actual entry kind found when `pool[idx]` is not a Float.
      */
    def float_(idx: Int)(using Frame): Float < Abort[TastyError] =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpFloat(value) => value
                // no cursor: constant pool accessor errors do not carry a stream position
                case other =>
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Float at pool[$idx], found ${tagName(other)}", 0L))

    /** Return a double constant at `idx`.
      *
      * Fails with a structured error including the actual entry kind found when `pool[idx]` is not a Double.
      */
    def double_(idx: Int)(using Frame): Double < Abort[TastyError] =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpDouble(value) => value
                // no cursor: constant pool accessor errors do not carry a stream position
                case other =>
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Double at pool[$idx], found ${tagName(other)}", 0L))

    /** Return the module name string (with '.' separators) for a CONSTANT_Module entry at `idx`.
      *
      * Fails with a structured chain error when `pool[idx]` is not a Module, or when its nameIdx does not point to a Utf8.
      */
    def moduleName(idx: Int)(using Frame): String < (Sync & Abort[TastyError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpModule(nameIdx) =>
                    utf8(nameIdx)
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Module at pool[$idx], found ${tagName(other)}", 0L))

    /** Return the package name string (with '/' separators) for a CONSTANT_Package entry at `idx`.
      *
      * Fails with a structured chain error when `pool[idx]` is not a Package, or when its nameIdx does not point to a Utf8.
      */
    def packageName(idx: Int)(using Frame): String < (Sync & Abort[TastyError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpPackage(nameIdx) =>
                    utf8(nameIdx)
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Package at pool[$idx], found ${tagName(other)}", 0L))

    /** Return (name, descriptor) for a CONSTANT_NameAndType entry.
      *
      * Fails with a structured chain error when `pool[idx]` is not a NameAndType, or when the name/descriptor indices do not point to Utf8.
      */
    def nameAndType(idx: Int)(using Frame): (String, String) < (Sync & Abort[TastyError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.NameAndType(nameIdx, descIdx) =>
                    utf8(nameIdx).map(name => utf8(descIdx).map(desc => (name, desc)))
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected NameAndType at pool[$idx], found ${tagName(other)}", 0L))

    /** Return (className, memberName, descriptor) for a field/method ref entry.
      *
      * Fails with a structured chain error when `pool[idx]` is not a Fieldref/Methodref/InterfaceMethodref, or when cross-entry references
      * (classIdx to ClassRef, nameAndTypeIdx to NameAndType) point to wrong-kind entries.
      */
    def memberRef(idx: Int)(using Frame): (String, String, String) < (Sync & Abort[TastyError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.Fieldref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case CpEntry.Methodref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case CpEntry.InterfaceMethodref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(
                        path,
                        s"Expected Fieldref/Methodref at pool[$idx], found ${tagName(other)}",
                        0L
                    ))

end ConstantPool

object ConstantPool:

    // Low-level big-endian integer reading from ByteView.
    // Classfiles use fixed-width big-endian integers, NOT TASTy LEB128.
    def readU1(view: ByteView)(using AllowUnsafe): Int = view.readByte() & 0xff
    def readU2(view: ByteView)(using AllowUnsafe): Int = (readU1(view) << 8) | readU1(view)
    def readU4(view: ByteView)(using AllowUnsafe): Int =
        (readU1(view) << 24) | (readU1(view) << 16) | (readU1(view) << 8) | readU1(view)
    def readU8(view: ByteView)(using AllowUnsafe): scala.Long =
        (readU4(view).toLong << 32) | (readU4(view).toLong & 0xffffffffL)

    /** Read the constant pool from `view` starting at the current cursor position.
      *
      * Expects the cursor to be positioned just after the major/minor version fields. Advances the cursor past all pool entries. The raw
      * classfile bytes are kept alive by the returned ConstantPool for lazy UTF-8 decoding.
      */
    def read(view: ByteView, path: String)(using Frame, AllowUnsafe): ConstantPool < (Sync & Abort[TastyError]) =
        Sync.defer {
            val count   = readU2(view)
            val entries = new Array[CpEntry | Null](count)

            // errorMsg and errorOffset are set inside the while loop when a non-recoverable format
            // error is detected. We cannot call Abort.fail inside Sync.defer, so we capture the
            // error and surface it as Abort.fail after the Sync.defer block completes.
            var errorMsg: String  = null
            var errorOffset: Long = 0L

            var idx = 1
            while idx < count && errorMsg == null do
                val tag = readU1(view)
                tag match
                    case ClassfileFormat.CONSTANT_Utf8 =>
                        val len = readU2(view)
                        val off = view.positionInt
                        // Advance cursor past the UTF-8 bytes
                        var i = 0
                        while i < len do
                            discard(view.readByte())
                            i += 1
                        // Copy bytes eagerly (cursor has advanced past them).
                        // Deferring only String materialization via Utf8.decode (cached on first access).
                        view match
                            case h: ByteView.Heap =>
                                entries(idx) = CpEntry.Utf8Lazy.init(h.copyBytes(off, off + len), 0, len)
                            case m: ByteView.Mapped =>
                                // Eager copy from mapped region into a heap array so that lazy decode
                                // does not depend on the mmap arena lifetime.
                                val buf = new Array[Byte](len)
                                var i   = 0
                                while i < len do
                                    buf(i) = m.peekByte((off + i).toLong)
                                    i += 1
                                entries(idx) = CpEntry.Utf8Lazy.init(buf, 0, len)
                        end match
                        idx += 1

                    case ClassfileFormat.CONSTANT_Integer =>
                        entries(idx) = CpEntry.CpInteger(readU4(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_Float =>
                        val bits = readU4(view)
                        entries(idx) = CpEntry.CpFloat(java.lang.Float.intBitsToFloat(bits))
                        idx += 1

                    case ClassfileFormat.CONSTANT_Long =>
                        entries(idx) = CpEntry.CpLong(readU8(view))
                        entries(idx + 1) = CpEntry.Hole
                        idx += 2

                    case ClassfileFormat.CONSTANT_Double =>
                        val bits = readU8(view)
                        entries(idx) = CpEntry.CpDouble(java.lang.Double.longBitsToDouble(bits))
                        entries(idx + 1) = CpEntry.Hole
                        idx += 2

                    case ClassfileFormat.CONSTANT_Class =>
                        entries(idx) = CpEntry.ClassRef(readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_String =>
                        entries(idx) = CpEntry.StringConst(readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_Fieldref =>
                        entries(idx) = CpEntry.Fieldref(readU2(view), readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_Methodref =>
                        entries(idx) = CpEntry.Methodref(readU2(view), readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_InterfaceMethodref =>
                        entries(idx) = CpEntry.InterfaceMethodref(readU2(view), readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_NameAndType =>
                        entries(idx) = CpEntry.NameAndType(readU2(view), readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_MethodHandle =>
                        entries(idx) = CpEntry.MethodHandle(readU1(view), readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_MethodType =>
                        entries(idx) = CpEntry.MethodType(readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_Dynamic =>
                        entries(idx) = CpEntry.Dynamic(readU2(view), readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_InvokeDynamic =>
                        entries(idx) = CpEntry.InvokeDynamic(readU2(view), readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_Module =>
                        entries(idx) = CpEntry.CpModule(readU2(view))
                        idx += 1

                    case ClassfileFormat.CONSTANT_Package =>
                        entries(idx) = CpEntry.CpPackage(readU2(view))
                        idx += 1

                    case unknown =>
                        errorMsg = s"Unknown constant pool tag $unknown at index $idx in $path"
                        errorOffset = view.position
                end match
            end while

            if errorMsg != null then Left((errorMsg, errorOffset))
            else Right(new ConstantPool(entries, path))
        }.map:
            case Right(pool)         => pool
            case Left((msg, offset)) => Abort.fail(TastyError.ClassfileFormatError(path, msg, offset))

end ConstantPool
