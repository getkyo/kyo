package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** IT surface for union type support.
  *
  * Union types (`Int | Float`) are the simplest non-trivial C union: two 4-byte variants that share storage. On every platform, the codegen
  * emits a runtime type match that writes the correct variant into a scratch-allocated union-sized region.
  *
  * The C functions in `kyo_it_unions.c` expect a `union { int i; float f; }` pointer, the same ABI as before. Only the Scala-side
  * representation changes from `@Ffi.Union case class` to bare `Int | Float`.
  */
trait ItUnionsBindings extends Ffi:
    /** Read the union's `i` field. The Scala binding passes `Int | Float` by pointer; the generator writes the value into the shared bytes
      * and this call returns it unchanged.
      */
    def kyoItUnionReadInt(u: Int | Float)(using AllowUnsafe): Int

    /** Read the union's `f` field, a bit reinterpretation of the same 4 bytes written via the int variant. */
    def kyoItUnionReadFloat(u: Int | Float)(using AllowUnsafe): Float

    /** Read the `x` field after passing the `UnionPoint` variant of an `Int | UnionPoint` union (#248). The codegen writes the
      * struct variant's fields into the union storage on every backend; this returns the first field unchanged.
      */
    def kyoItUnionStructX(u: Int | UnionPoint)(using AllowUnsafe): Int

    /** Read the `y` field of the `UnionPoint` variant: the second field, written at offset 4 of the union. */
    def kyoItUnionStructY(u: Int | UnionPoint)(using AllowUnsafe): Int

    /** Read the `i` (int) variant of the same `Int | UnionPoint` union, confirming the primitive variant still marshals after a
      * struct variant was added.
      */
    def kyoItUnionStructI(u: Int | UnionPoint)(using AllowUnsafe): Int

    /** Read the `tag` field after passing the `HandleHolder` variant of an `Int | HandleHolder` union (#248). The struct variant
      * has a pointer (`Ffi.Handle`) field, so it exercises pointer-field marshalling inside a union struct variant on every backend.
      */
    def kyoItUnionHolderTag(u: Int | HandleHolder)(using AllowUnsafe): Int

    /** Dereference the `Ffi.Handle` field of the `HandleHolder` variant and read its stored value, proving the pointer field was
      * written into the union storage correctly on every backend.
      */
    def kyoItUnionHolderValue(u: Int | HandleHolder)(using AllowUnsafe): Int

    /** Read the long view after passing the `Long` variant of a `Long | Double` union (#251). The Long variant must write its 8
      * raw bytes, so this returns the exact Long passed in on every backend.
      */
    def kyoItUnionLongView(u: Long | Double)(using AllowUnsafe): Long

    /** Read the double view after passing the `Double` variant of the same `Long | Double` union, confirming the Double variant
      * still marshals.
      */
    def kyoItUnionDoubleView(u: Long | Double)(using AllowUnsafe): Double

    /** Read the plain `tag` field of a `Tagged` struct whose other field is a union (`Int | Float`) (#253). Exercises a union
      * field inside a struct parameter, the documented `Event(tag, data: Int | Float)` shape, on every backend.
      */
    def kyoItTaggedTag(t: Tagged)(using AllowUnsafe): Int

    /** Read the int view of the `Tagged` struct's union field after passing the `Int` variant. */
    def kyoItTaggedInt(t: Tagged)(using AllowUnsafe): Int

    /** Read the float view of the `Tagged` struct's union field after passing the `Float` variant. */
    def kyoItTaggedFloat(t: Tagged)(using AllowUnsafe): Float

    /** Read the plain `label` field of a `Boxed` struct whose union field has a STRUCT variant (`Int | UnionPoint`) (#253). */
    def kyoItBoxedLabel(b: Boxed)(using AllowUnsafe): Int

    /** Read the int view of the `Boxed` struct's union field after passing the `Int` variant. */
    def kyoItBoxedInt(b: Boxed)(using AllowUnsafe): Int

    /** Read the `x` field of the `UnionPoint` (struct) variant of the `Boxed` struct's union field. */
    def kyoItBoxedX(b: Boxed)(using AllowUnsafe): Int

    /** Read the `y` field of the `UnionPoint` (struct) variant of the `Boxed` struct's union field. */
    def kyoItBoxedY(b: Boxed)(using AllowUnsafe): Int
end ItUnionsBindings

/** A two-int struct used as a union variant (`Int | UnionPoint`) to exercise struct-variant marshalling in a union parameter (#248). */
case class UnionPoint(x: Int, y: Int)

/** A struct with a union FIELD (`Int | Float`), the documented `Event(tag, data)` shape, to exercise union-in-struct-field
  * marshalling in a struct parameter on every backend (#253).
  */
case class Tagged(tag: Int, data: Int | Float)

/** A struct whose union field has a STRUCT variant (`Int | UnionPoint`), to exercise a union-in-struct-field whose variant
  * is itself a struct (the registered koffi union references another registered struct type) on every backend (#253).
  */
case class Boxed(label: Int, payload: Int | UnionPoint)

/** A struct with a pointer (`Ffi.Handle`) field, used as a union variant (`Int | HandleHolder`) to exercise pointer-field
  * marshalling inside a union struct variant on every backend (#248). `h` points at a `kyo_it_create_handle` allocation.
  */
case class HandleHolder(tag: Int, h: Ffi.Handle[ItHandle])

object ItUnionsBindings extends Ffi.Config(library = "kyo_it_bundled")
