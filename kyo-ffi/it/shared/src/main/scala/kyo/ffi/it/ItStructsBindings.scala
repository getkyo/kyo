package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.Fiber
import kyo.ffi.Ffi

/** Nested-struct / packed-struct / multi-value return exercises.
  *
  * Struct parameters are marshalled as POINTERS on every platform (JVM's Foreign Linker uses the ADDRESS layout, Scala Native uses
  * `Ptr[CStruct...]`, Scala.js koffi uses a koffi-owned struct pointer). The bundled C source `kyo_it_structs.c` reflects that by accepting
  * pointer parameters.
  *
  * `Circle` nests `Center`: the Scala case-class types must match the C struct layout exactly. `Packed` is declared in `packedStructs` so
  * the codegen emits `__attribute__((packed))` layouts on every platform, its two fields are uniformly int32-aligned to avoid the
  * Panama-rejected mixed-alignment case documented in the scripted `structs-end-to-end` fixture.
  *
  * `Pair` is a multi-value return (C returns `sum`, out-param carries `product`). Field order matters: the first field of the case class
  * goes to the C return value, every subsequent field to an out-pointer argument.
  */
// `derives CanEqual` so the by-value struct-return tests can compare a whole returned case class with `shouldBe`
// under the module's `-language:strictEquality`.
case class Center(x: Double, y: Double) derives CanEqual
case class Circle(center: Center, radius: Double) derives CanEqual
case class Packed(tag: Int, value: Int)
case class Pair(sum: Int, product: Int)
case class Box(v: Int) derives CanEqual

trait ItStructsBindings extends Ffi:
    /** Area of a `Circle`, exercises nested-struct parameter marshalling. */
    def kyoItCircleArea(c: Circle)(using AllowUnsafe): Double

    /** Translate the center by `(dx, dy)` and return the sum of the translated center coords and radius. Exercises nested-struct field
      * reads on both inner and outer struct levels plus a mix of struct and primitive parameters.
      */
    def kyoItCircleSum(c: Circle, dx: Double, dy: Double)(using AllowUnsafe): Double

    /** Packed-struct parameter read, returns the value weighted by 100 when tag == 1, value unchanged otherwise. */
    def kyoItPackedValue(p: Packed)(using AllowUnsafe): Int

    /** Multi-value return via a case class. First field (`sum`) is the C return; additional fields (`product`) come from out-pointer
      * arguments the codegen synthesizes.
      */
    def kyoItMakePair(a: Int, b: Int)(using AllowUnsafe): Pair

    /** By-value struct return (`@Ffi.byValue`). The C function is `void kyo_it_make_circle(Circle* out, double, double, double)`: the
      * struct out-pointer is the FIRST C parameter (the same out-pointer convention struct parameters use), the C side fills it, and
      * kyo-ffi marshals the filled nested `Circle` back into the case class. Exercises the unified out-first struct-return ABI on every
      * backend.
      */
    @Ffi.byValue
    def kyoItMakeCircle(cx: Double, cy: Double, r: Double)(using AllowUnsafe): Circle

    /** By-value struct return with a SINGLE field, proving `@Ffi.byValue` permits one field where a multi-value return cannot (a
      * multi-value return needs at least two fields). C: `void kyo_it_make_box(Box* out, int32_t v)`.
      */
    @Ffi.byValue
    def kyoItMakeBox(v: Int)(using AllowUnsafe): Box

    /** `@Ffi.blocking` + `@Ffi.byValue`: a blocking by-value struct return. The struct out-pointer must survive the blocking dispatch
      * boundary, which on JS means the buffer is filled on a libuv worker and decoded inside koffi's completion callback (on JVM/Native
      * the downcall runs synchronously on the carrier). Returns a `Fiber.Unsafe` the caller awaits with `.safe.get`.
      */
    @Ffi.blocking
    @Ffi.byValue
    def kyoItMakeCircleBlocking(cx: Double, cy: Double, r: Double)(using AllowUnsafe): Fiber.Unsafe[Circle, Any]
end ItStructsBindings

object ItStructsBindings extends Ffi.Config(library = "kyo_it_bundled", packedStructs = Set("Packed"))
