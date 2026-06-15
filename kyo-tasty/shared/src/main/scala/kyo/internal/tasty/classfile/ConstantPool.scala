package kyo.internal.tasty.classfile

import kyo.*
import kyo.internal.tasty.binary.ByteView

/** Lazily-decoded JVM constant pool backed by `Array[Maybe[ConstantPoolEntry]]`.
  *
  * Slot 0 is always `Maybe.Absent` (JVMS §4.1: "the constant_pool table is indexed from 1 to
  * constant_pool_count-1"). The second slot of every Long/Double entry is
  * `Maybe.Present(ConstantPoolEntry.Hole)` per JVMS §4.4.5. All other entries are populated during
  * `ConstantPool.read`. All accessors return effects: `Abort[TastyError]` for format errors.
  */
final class ConstantPool(
    private val entries: Array[Maybe[ConstantPoolEntry]],
    val path: String
):

    /** Validate an index and return the entry.
      *
      * Returns a structured error for out-of-range indices, absent slots (slot 0 and unread
      * entries), and Long/Double hole slots.
      */
    private def entry(idx: Int)(using Frame): ConstantPoolEntry < Abort[TastyError] =
        if idx < 1 || idx >= entries.length then
            // no cursor: constant pool entry lookup by index does not have a stream position
            Abort.fail(TastyError.ClassfileFormatError(path, s"Constant pool index $idx out of bounds [1, ${entries.length - 1}]", 0L))
        else
            entries(idx) match
                case Maybe.Absent =>
                    // no cursor: constant pool slot validation does not have a stream position
                    Abort.fail(TastyError.ClassfileFormatError(
                        path,
                        s"Constant pool slot $idx is absent (slot 0 or unread entry)",
                        0L
                    ))
                case Maybe.Present(e) =>
                    if e eq ConstantPoolEntry.Hole then
                        // no cursor: constant pool slot validation does not have a stream position
                        Abort.fail(TastyError.ClassfileFormatError(
                            path,
                            s"Constant pool slot $idx is the unused second slot of a Long/Double entry (invalid reference)",
                            0L
                        ))
                    else e

    /** Return a short tag name for a ConstantPoolEntry, used in cross-entry error messages. */
    private def tagName(e: ConstantPoolEntry): String = e match
        case _: ConstantPoolEntry.Utf8Lazy           => "Utf8"
        case _: ConstantPoolEntry.Utf8Decoded        => "Utf8"
        case _: ConstantPoolEntry.ClassRef           => "ClassRef"
        case _: ConstantPoolEntry.NameAndType        => "NameAndType"
        case _: ConstantPoolEntry.Fieldref           => "Fieldref"
        case _: ConstantPoolEntry.Methodref          => "Methodref"
        case _: ConstantPoolEntry.InterfaceMethodref => "InterfaceMethodref"
        case _: ConstantPoolEntry.Integer            => "Integer"
        case _: ConstantPoolEntry.Float              => "Float"
        case _: ConstantPoolEntry.Long               => "Long"
        case _: ConstantPoolEntry.Double             => "Double"
        case _: ConstantPoolEntry.StringConst        => "String"
        case _: ConstantPoolEntry.MethodHandle       => "MethodHandle"
        case _: ConstantPoolEntry.MethodType         => "MethodType"
        case _: ConstantPoolEntry.Dynamic            => "Dynamic"
        case _: ConstantPoolEntry.InvokeDynamic      => "InvokeDynamic"
        case _: ConstantPoolEntry.Module             => "Module"
        case _: ConstantPoolEntry.Package            => "Package"
        case _: ConstantPoolEntry.Hole.type          => "Hole"

    /** Decode UTF-8 constant at `idx`.
      *
      * Fails with a structured error if `pool[idx]` is not a Utf8 entry, including the actual entry kind found.
      */
    def utf8(idx: Int)(using Frame): String < (Sync & Abort[TastyError]) =
        entry(idx).map { poolEntry =>
            poolEntry match
                case u: ConstantPoolEntry.Utf8Lazy =>
                    // Sync.Unsafe.defer provides AllowUnsafe for the AtomicRef decode/cache access.
                    Sync.Unsafe.defer {
                        u.decode()
                    }
                case ConstantPoolEntry.Utf8Decoded(v) =>
                    v
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Utf8 at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `utf8`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, String]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S` body.
      */
    def utf8Unsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, String] =
        Sync.Unsafe.evalOrThrow(Abort.run(utf8(idx)))

    /** Return the binary class name (with '/' separators) for a CONSTANT_Class entry at `idx`.
      *
      * Fails with a structured chain error when `pool[idx]` is not a ClassRef, or when the nameIdx it contains does not point to a Utf8.
      */
    def classRef(idx: Int)(using Frame): String < (Sync & Abort[TastyError]) =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.ClassRef(nameIdx) =>
                    utf8(nameIdx)
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected ClassRef at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `classRef`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, String]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S` body.
      */
    def classRefUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, String] =
        Sync.Unsafe.evalOrThrow(Abort.run(classRef(idx)))

    /** Return an integer constant at `idx`.
      *
      * Fails with a structured error including the actual entry kind found when `pool[idx]` is not an Integer.
      */
    def integer(idx: Int)(using Frame): Int < Abort[TastyError] =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.Integer(value) => value
                // no cursor: constant pool accessor errors do not carry a stream position
                case other =>
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Integer at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `integer`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, Int]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S` body.
      */
    def integerUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, Int] =
        Sync.Unsafe.evalOrThrow(Abort.run(integer(idx)))

    /** Return a long constant at `idx`.
      *
      * Fails with a structured error including the actual entry kind found when `pool[idx]` is not a Long.
      */
    def long_(idx: Int)(using Frame): Long < Abort[TastyError] =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.Long(value) => value
                // no cursor: constant pool accessor errors do not carry a stream position
                case other => Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Long at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `long_`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, Long]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S` body.
      */
    def longUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, Long] =
        Sync.Unsafe.evalOrThrow(Abort.run(long_(idx)))

    /** Return a float constant at `idx`.
      *
      * Fails with a structured error including the actual entry kind found when `pool[idx]` is not a Float.
      */
    def float_(idx: Int)(using Frame): Float < Abort[TastyError] =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.Float(value) => value
                // no cursor: constant pool accessor errors do not carry a stream position
                case other =>
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Float at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `float_`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, Float]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S` body.
      */
    def floatUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, Float] =
        Sync.Unsafe.evalOrThrow(Abort.run(float_(idx)))

    /** Return a double constant at `idx`.
      *
      * Fails with a structured error including the actual entry kind found when `pool[idx]` is not a Double.
      */
    def double_(idx: Int)(using Frame): Double < Abort[TastyError] =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.Double(value) => value
                // no cursor: constant pool accessor errors do not carry a stream position
                case other =>
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Double at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `double_`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, Double]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S` body.
      */
    def doubleUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, Double] =
        Sync.Unsafe.evalOrThrow(Abort.run(double_(idx)))

    /** Return the module name string (with '.' separators) for a CONSTANT_Module entry at `idx`.
      *
      * Fails with a structured chain error when `pool[idx]` is not a Module, or when its nameIdx does not point to a Utf8.
      */
    def moduleName(idx: Int)(using Frame): String < (Sync & Abort[TastyError]) =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.Module(nameIdx) =>
                    utf8(nameIdx)
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Module at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `moduleName`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, String]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S` body.
      */
    def moduleNameUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, String] =
        Sync.Unsafe.evalOrThrow(Abort.run(moduleName(idx)))

    /** Return the package name string (with '/' separators) for a CONSTANT_Package entry at `idx`.
      *
      * Fails with a structured chain error when `pool[idx]` is not a Package, or when its nameIdx does not point to a Utf8.
      */
    def packageName(idx: Int)(using Frame): String < (Sync & Abort[TastyError]) =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.Package(nameIdx) =>
                    utf8(nameIdx)
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected Package at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `packageName`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, String]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S` body.
      */
    def packageNameUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, String] =
        Sync.Unsafe.evalOrThrow(Abort.run(packageName(idx)))

    /** Return (name, descriptor) for a CONSTANT_NameAndType entry.
      *
      * Fails with a structured chain error when `pool[idx]` is not a NameAndType, or when the name/descriptor indices do not point to Utf8.
      */
    def nameAndType(idx: Int)(using Frame): (String, String) < (Sync & Abort[TastyError]) =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.NameAndType(nameIdx, descIdx) =>
                    utf8(nameIdx).map(name => utf8(descIdx).map(desc => (name, desc)))
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(path, s"Expected NameAndType at pool[$idx], found ${tagName(other)}", 0L))
        }

    /** Unsafe-tier sibling of `nameAndType`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, (String, String)]`. Used by inventory-site migrations that need to call constant-pool accessors from a non-`< S`
      * body.
      */
    def nameAndTypeUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, (String, String)] =
        Sync.Unsafe.evalOrThrow(Abort.run(nameAndType(idx)))

    /** Return (className, memberName, descriptor) for a field/method ref entry.
      *
      * Fails with a structured chain error when `pool[idx]` is not a Fieldref/Methodref/InterfaceMethodref, or when cross-entry references
      * (classIdx to ClassRef, nameAndTypeIdx to NameAndType) point to wrong-kind entries.
      */
    def memberRef(idx: Int)(using Frame): (String, String, String) < (Sync & Abort[TastyError]) =
        entry(idx).map { poolEntry =>
            poolEntry match
                case ConstantPoolEntry.Fieldref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case ConstantPoolEntry.Methodref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case ConstantPoolEntry.InterfaceMethodref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case other =>
                    // no cursor: constant pool accessor errors do not carry a stream position
                    Abort.fail(TastyError.ClassfileFormatError(
                        path,
                        s"Expected Fieldref/Methodref at pool[$idx], found ${tagName(other)}",
                        0L
                    ))
        }

    /** Unsafe-tier sibling of `memberRef`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, (String, String, String)]`. Used by inventory-site migrations that need to call constant-pool accessors from a
      * non-`< S` body.
      */
    def memberRefUnsafe(idx: Int)(using Frame, AllowUnsafe): Result[TastyError, (String, String, String)] =
        Sync.Unsafe.evalOrThrow(Abort.run(memberRef(idx)))

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
    def read(view: ByteView, path: String)(using Frame, AllowUnsafe): Result[TastyError, ConstantPool] =
        val count   = readU2(view)
        val entries = new Array[Maybe[ConstantPoolEntry]](count)
        // Initialize all slots to Absent so slot 0 and any skipped entries are well-typed.
        java.util.Arrays.fill(entries.asInstanceOf[Array[AnyRef]], Maybe.Absent)

        // errorMsg and errorOffset are set inside the while loop when a non-recoverable format
        // error is detected. The captured error is surfaced as Result.Failure after the loop.
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
                            entries(idx) = Maybe.Present(ConstantPoolEntry.Utf8Lazy.init(h.copyBytes(off, off + len), 0, len))
                        case m: ByteView.Mapped =>
                            // Eager copy from mapped region into a heap array so that lazy decode
                            // does not depend on the mmap arena lifetime.
                            val bytes = new Array[Byte](len)
                            var i     = 0
                            while i < len do
                                bytes(i) = m.peekByte((off + i).toLong)
                                i += 1
                            entries(idx) = Maybe.Present(ConstantPoolEntry.Utf8Lazy.init(bytes, 0, len))
                    end match
                    idx += 1

                case ClassfileFormat.CONSTANT_Integer =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Integer(readU4(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_Float =>
                    val bits = readU4(view)
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Float(java.lang.Float.intBitsToFloat(bits)))
                    idx += 1

                case ClassfileFormat.CONSTANT_Long =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Long(readU8(view)))
                    entries(idx + 1) = Maybe.Present(ConstantPoolEntry.Hole)
                    idx += 2

                case ClassfileFormat.CONSTANT_Double =>
                    val bits = readU8(view)
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Double(java.lang.Double.longBitsToDouble(bits)))
                    entries(idx + 1) = Maybe.Present(ConstantPoolEntry.Hole)
                    idx += 2

                case ClassfileFormat.CONSTANT_Class =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.ClassRef(readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_String =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.StringConst(readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_Fieldref =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Fieldref(readU2(view), readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_Methodref =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Methodref(readU2(view), readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_InterfaceMethodref =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.InterfaceMethodref(readU2(view), readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_NameAndType =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.NameAndType(readU2(view), readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_MethodHandle =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.MethodHandle(readU1(view), readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_MethodType =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.MethodType(readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_Dynamic =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Dynamic(readU2(view), readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_InvokeDynamic =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.InvokeDynamic(readU2(view), readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_Module =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Module(readU2(view)))
                    idx += 1

                case ClassfileFormat.CONSTANT_Package =>
                    entries(idx) = Maybe.Present(ConstantPoolEntry.Package(readU2(view)))
                    idx += 1

                case unknown =>
                    errorMsg = s"Unknown constant pool tag $unknown at index $idx in $path"
                    errorOffset = view.position
            end match
        end while

        if errorMsg != null then Result.Failure(TastyError.ClassfileFormatError(path, errorMsg, errorOffset))
        else Result.Success(new ConstantPool(entries, path))
    end read

end ConstantPool
