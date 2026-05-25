package kyo.internal.reflect.classfile

import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.symbol.Interner

/** Constant pool entry ADT.
  *
  * Entries are indexed 1..count-1 (slot 0 is unused per the JVM spec). UTF-8 entries are stored as raw byte offsets and decoded on first
  * access via the Interner.
  */
sealed trait CpEntry

object CpEntry:

    /** Lazy UTF-8: stores raw bytes and decodes on first utf8() call. */
    final class Utf8Lazy(val bytes: Array[Byte], val offset: Int, val length: Int) extends CpEntry:
        private val cached = new AtomicReference[Interner.Entry | Null](null)

        def decode(interner: Interner): Interner.Entry =
            val r = cached.get()
            if r != null then r
            else
                val fresh = interner.intern(bytes, offset, length)
                cached.set(fresh)
                fresh
            end if
        end decode
    end Utf8Lazy

    final case class Utf8Decoded(entry: Interner.Entry)                     extends CpEntry
    final case class ClassRef(nameIdx: Int)                                 extends CpEntry
    final case class NameAndType(nameIdx: Int, descriptorIdx: Int)          extends CpEntry
    final case class Fieldref(classIdx: Int, nameAndTypeIdx: Int)           extends CpEntry
    final case class Methodref(classIdx: Int, nameAndTypeIdx: Int)          extends CpEntry
    final case class InterfaceMethodref(classIdx: Int, nameAndTypeIdx: Int) extends CpEntry
    final case class CpInteger(value: Int)                                  extends CpEntry
    final case class CpFloat(value: scala.Float)                            extends CpEntry
    final case class CpLong(value: scala.Long)                              extends CpEntry
    final case class CpDouble(value: scala.Double)                          extends CpEntry
    final case class StringConst(stringIdx: Int)                            extends CpEntry
    final case class MethodHandle(referenceKind: Int, referenceIdx: Int)    extends CpEntry
    final case class MethodType(descriptorIdx: Int)                         extends CpEntry
    final case class Dynamic(bootstrapIdx: Int, nameAndTypeIdx: Int)        extends CpEntry
    final case class InvokeDynamic(bootstrapIdx: Int, nameAndTypeIdx: Int)  extends CpEntry
    final case class CpModule(nameIdx: Int)                                 extends CpEntry
    final case class CpPackage(nameIdx: Int)                                extends CpEntry

    /** Sentinel for the second slot occupied by Long/Double entries. */
    case object Hole extends CpEntry

end CpEntry

/** Lazily-decoded JVM constant pool.
  *
  * All accessors return effects: `Abort[ReflectError]` for format errors.
  */
final class ConstantPool(
    private val entries: Array[CpEntry | Null],
    private val interner: Interner,
    val path: String
):

    /** Validate an index and return the entry. */
    private def entry(idx: Int)(using Frame): CpEntry < Abort[ReflectError] =
        if idx < 1 || idx >= entries.length then
            Abort.fail(ReflectError.ClassfileFormatError(path, s"Constant pool index $idx out of bounds [1, ${entries.length - 1}]"))
        else
            entries(idx) match
                case null =>
                    Abort.fail(ReflectError.ClassfileFormatError(
                        path,
                        s"Constant pool slot $idx is a Long/Double hole (invalid reference)"
                    ))
                case e =>
                    e

    /** Decode UTF-8 constant at `idx`. */
    def utf8(idx: Int)(using Frame): String < (Sync & Abort[ReflectError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case u: CpEntry.Utf8Lazy =>
                    Sync.defer(u.decode(interner).string.get())
                case CpEntry.Utf8Decoded(e) =>
                    Sync.defer(e.string.get())
                case _ =>
                    Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Utf8 at pool[$idx]"))

    /** Return the binary class name (with '/' separators) for a CONSTANT_Class entry at `idx`. */
    def classRef(idx: Int)(using Frame): String < (Sync & Abort[ReflectError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.ClassRef(nameIdx) =>
                    utf8(nameIdx)
                case _ =>
                    Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Class at pool[$idx]"))

    /** Return an integer constant at `idx`. */
    def integer(idx: Int)(using Frame): Int < Abort[ReflectError] =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpInteger(value) => value
                case _                        => Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Integer at pool[$idx]"))

    /** Return a long constant at `idx`. */
    def long_(idx: Int)(using Frame): Long < Abort[ReflectError] =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpLong(value) => value
                case _                     => Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Long at pool[$idx]"))

    /** Return a float constant at `idx`. */
    def float_(idx: Int)(using Frame): Float < Abort[ReflectError] =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpFloat(value) => value
                case _                      => Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Float at pool[$idx]"))

    /** Return a double constant at `idx`. */
    def double_(idx: Int)(using Frame): Double < Abort[ReflectError] =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.CpDouble(value) => value
                case _                       => Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Double at pool[$idx]"))

    /** Return (name, descriptor) for a CONSTANT_NameAndType entry. */
    def nameAndType(idx: Int)(using Frame): (String, String) < (Sync & Abort[ReflectError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.NameAndType(nameIdx, descIdx) =>
                    utf8(nameIdx).map(name => utf8(descIdx).map(desc => (name, desc)))
                case _ =>
                    Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected NameAndType at pool[$idx]"))

    /** Return (className, memberName, descriptor) for a field/method ref entry. */
    def memberRef(idx: Int)(using Frame): (String, String, String) < (Sync & Abort[ReflectError]) =
        entry(idx).map: cpEntry =>
            cpEntry match
                case CpEntry.Fieldref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case CpEntry.Methodref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case CpEntry.InterfaceMethodref(classIdx, natIdx) =>
                    classRef(classIdx).map(cls => nameAndType(natIdx).map((name, desc) => (cls, name, desc)))
                case _ =>
                    Abort.fail(ReflectError.ClassfileFormatError(path, s"Expected Fieldref/Methodref at pool[$idx]"))

end ConstantPool

object ConstantPool:

    // Low-level big-endian integer reading from ByteView.
    // Classfiles use fixed-width big-endian integers, NOT TASTy LEB128.
    def readU1(view: ByteView): Int = view.readByte() & 0xff
    def readU2(view: ByteView): Int = (readU1(view) << 8) | readU1(view)
    def readU4(view: ByteView): Int =
        (readU1(view) << 24) | (readU1(view) << 16) | (readU1(view) << 8) | readU1(view)
    def readU8(view: ByteView): scala.Long =
        (readU4(view).toLong << 32) | (readU4(view).toLong & 0xffffffffL)

    /** Read the constant pool from `view` starting at the current cursor position.
      *
      * Expects the cursor to be positioned just after the major/minor version fields. Advances the cursor past all pool entries. The raw
      * classfile bytes are kept alive by the returned ConstantPool for lazy UTF-8 decoding.
      */
    def read(view: ByteView, interner: Interner, path: String)(using Frame): ConstantPool < (Sync & Abort[ReflectError]) =
        Sync.defer {
            val count   = readU2(view)
            val entries = new Array[CpEntry | Null](count)

            var idx = 1
            while idx < count do
                val tag = readU1(view)
                tag match
                    case ClassfileFormat.CONSTANT_Utf8 =>
                        val len = readU2(view)
                        val off = view.position
                        // Advance cursor past the UTF-8 bytes
                        var i = 0
                        while i < len do
                            discard(view.readByte())
                            i += 1
                        // Copy bytes eagerly (cursor has advanced past them).
                        // Deferring only String materialization via the Interner.
                        val copiedBytes = view match
                            case h: ByteView.Heap => h.copyBytes(off, off + len)
                            case _                => throw new IllegalStateException(s"Unexpected ByteView variant in $path")
                        entries(idx) = new CpEntry.Utf8Lazy(copiedBytes, 0, len)
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
                        throw new IllegalStateException(
                            s"Unknown constant pool tag $unknown at index $idx in $path"
                        )
                end match
            end while

            new ConstantPool(entries, interner, path)
        }

end ConstantPool
