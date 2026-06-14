package se

import kyo.AllowUnsafe
import kyo.ffi.*

// Nested struct: Line contains two Points.
case class Point(x: Int, y: Int)
case class Line(a: Point, b: Point)
// Packed struct: two int32 fields. We declare this packed to exercise the
// packedStructs code path. Both fields share the same alignment so Panama
// accepts the layout (mixed-alignment packed structs are a known JvmEmitter
// limitation that produces "Invalid alignment constraint" at load time).
case class Packed(tag: Int, value: Int)
// Multi-value return: sum (C return) + product (out-param).
case class Pair(sum: Int, product: Int)
// Struct return with a String field: `code` echoes the C return value; `message`
// is populated from an out-pointer pointing at a statically-allocated C string.
case class StatusInfo(code: Int, message: String)

trait SeBindings extends Ffi:
    // Nested struct in param position.
    def seLineSum(line: Line)(using AllowUnsafe): Int

    // Packed struct in param position.
    def sePackedCompute(p: Packed)(using AllowUnsafe): Int

    // Multi-value return (sum + product).
    def sePair(a: Int, b: Int)(using AllowUnsafe): Pair

    // Struct return with a String field. C returns `code` and writes a pointer
    // to a statically-allocated message into the out-param.
    def seGetStatus(code: Int)(using AllowUnsafe): StatusInfo
end SeBindings

object SeBindings extends Ffi.Config(library = "se_lib", packedStructs = Set("Packed"))
