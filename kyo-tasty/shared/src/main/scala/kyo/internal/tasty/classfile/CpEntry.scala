package kyo.internal.tasty.classfile

import kyo.*
import kyo.internal.tasty.binary.Utf8

/** Constant pool entry ADT.
  *
  * Entries are indexed 1..count-1 (slot 0 is unused per the JVM spec). UTF-8 entries are stored as raw byte offsets and decoded on first
  * access via Utf8.decode.
  */
sealed abstract class CpEntry

object CpEntry:

    /** Lazy UTF-8: stores raw bytes and decodes on first utf8() call.
      *
      * The `cached` field uses `AtomicRef.Unsafe[Maybe[String]]` with `Absent` as the not-yet-decoded sentinel. Construction requires
      * AllowUnsafe via the companion factory; the constructor is private to enforce that invariant.
      */
    final class Utf8Lazy private (
        val bytes: Array[Byte],
        val offset: Int,
        val length: Int,
        private val cached: AtomicRef.Unsafe[Maybe[String]]
    ) extends CpEntry:

        def decode()(using AllowUnsafe): String =
            cached.get() match
                case Present(s) => s
                case Absent =>
                    val fresh = Utf8.decode(bytes, offset, length)
                    cached.set(Present(fresh))
                    fresh
        end decode
    end Utf8Lazy

    object Utf8Lazy:
        /** Allocate a lazy UTF-8 entry. Requires AllowUnsafe because AtomicRef.Unsafe.init is an unsafe-tier allocation. */
        def init(bytes: Array[Byte], offset: Int, length: Int)(using AllowUnsafe): Utf8Lazy =
            new Utf8Lazy(bytes, offset, length, AtomicRef.Unsafe.init[Maybe[String]](Absent))
    end Utf8Lazy

    final case class Utf8Decoded(value: String)                             extends CpEntry
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
