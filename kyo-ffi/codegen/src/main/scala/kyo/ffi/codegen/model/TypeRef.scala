package kyo.ffi.codegen.model

/** Canonical type representation used across the code generator.
  *
  * Every supported FFI parameter or return type maps to one of these. The generator walks trait signatures via TASTy and produces
  * [[TypeRef]] values; emitters consume them to produce platform-specific code.
  */
sealed trait TypeRef derives CanEqual

object TypeRef:
    // Primitives
    case object BooleanT extends TypeRef
    case object ByteT    extends TypeRef
    case object ShortT   extends TypeRef
    case object IntT     extends TypeRef
    case object LongT    extends TypeRef
    case object FloatT   extends TypeRef
    case object DoubleT  extends TypeRef
    case object UnitT    extends TypeRef

    // Reference-like
    case object StringT                     extends TypeRef
    final case class ArrayT(elem: TypeRef)  extends TypeRef
    final case class BufferT(elem: TypeRef) extends TypeRef
    final case class StructT(name: String)  extends TypeRef

    // Phantom-typed opaque pointer handle (Ffi.Handle[A])
    final case class HandleT(typeArgFqcn: String) extends TypeRef

    // C enum (marshalled as Int)
    final case class EnumT(fqcn: String) extends TypeRef

    // Callback
    final case class FnPtrT(params: List[TypeRef], ret: TypeRef) extends TypeRef

    // Union type (A | B | C), detected from Scala 3 OrType in TASTy
    final case class UnionT(variants: List[TypeRef]) extends TypeRef

    // Guard (appears only as a method parameter, not as a field/return).
    case object GuardT extends TypeRef

    /** True if the TypeRef is a primitive (no marshalling). */
    def isPrimitive(t: TypeRef): Boolean = t match
        case BooleanT => true
        case ByteT    => true
        case ShortT   => true
        case IntT     => true
        case LongT    => true
        case FloatT   => true
        case DoubleT  => true
        case UnitT    => true
        case _        => false
end TypeRef
