package kyo.ffi.codegen.fixtures

import kyo.*
import kyo.ffi.*
import kyo.ffi.Buffer

/** Test fixture trait used by `FfiGeneratorSpec`. Compiled as part of `kyo-ffi-codegen/Test`; its TASTy is fed into `FfiGenerator.generate`
  * for end-to-end extraction + emission checks.
  *
  * Every binding method takes a trailing `(using AllowUnsafe)`: the FFI binding layer is the unsafe tier. A `@Ffi.blocking` method returns
  * `Fiber.Unsafe[…, Any]`, the blocking downcall is surfaced as a fiber.
  */
trait FixtureBindings extends Ffi:
    /** Plain non-blocking primitive call, exercises the happy path. */
    def add(a: Int, b: Int)(using AllowUnsafe): Int

    /** Blocking call with a [[Buffer]] parameter, exercises `@Ffi.blocking` + array-in-signature handling. */
    @Ffi.blocking
    def blockingRead(fd: Int, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]
end FixtureBindings

object FixtureBindings extends Ffi.Config(library = "kyo_fixture")

/** Fixture exercising the per-binding `Ffi.Config.scratchSize` override. The generator's emitted impl for this trait must pre-size the
  * per-thread scratch block to 128 KiB via `Scratch.currentFor("...", 131072L)` instead of falling back to `Scratch.configuredSize`.
  */
trait SizedScratchBindings extends Ffi:
    def noop(x: Int)(using AllowUnsafe): Int
end SizedScratchBindings

object SizedScratchBindings extends Ffi.Config(library = "kyo_sized", scratchSize = Present(128 * 1024))

/** Fixture exercising top-level borrowed-return shapes.
  *
  *   - `getenvLike` returns a borrowed `String`, the C callee retains ownership (environment-owned storage).
  *   - `mallocChunk` returns a borrowed `Buffer[Byte]` whose element count is inferred from the `n` parameter.
  *
  * The generator's inspector must produce `ReturnShape.BorrowedString` / `ReturnShape.BorrowedBuffer` respectively; the validator must
  * accept both. Invalid `Borrowed[...]` applications are covered by separate synthetic-spec tests in `TypeValidatorSpec`.
  */
trait BorrowedReturnBindings extends Ffi:
    def getenvLike(name: String)(using AllowUnsafe): Ffi.Borrowed[String]

    def mallocChunk(n: Long)(using AllowUnsafe): Ffi.Borrowed[Buffer[Byte]]
end BorrowedReturnBindings

object BorrowedReturnBindings extends Ffi.Config(library = "kyo_borrowed")

/** Fixture exercising Scala 3 union type support.
  *
  * `Int | Float` is a two-variant union. The inspector must detect the `OrType` in TASTy and produce a `UnionT(List(IntT, FloatT))`.
  */
trait UnionBindings extends Ffi:
    /** Identity pass-through, takes a union by pointer, returns the int view. */
    def roundtrip(u: Int | Float)(using AllowUnsafe): Int
end UnionBindings

object UnionBindings extends Ffi.Config(library = "kyo_union_fixture")

/** Negative fixture: union type with a `String` variant, the inspector must reject it. */
trait BadUnionStringBindings extends Ffi:
    def roundtrip(u: Int | String)(using AllowUnsafe): Int
end BadUnionStringBindings

object BadUnionStringBindings extends Ffi.Config(library = "kyo_bad_union_string")

/** Negative fixture: union type as return, the inspector must reject it. */
trait BadUnionReturnBindings extends Ffi:
    def getValue()(using AllowUnsafe): Int | Float
end BadUnionReturnBindings

object BadUnionReturnBindings extends Ffi.Config(library = "kyo_bad_union_return")

/** Fixture exercising variadic (`Any*`) method support.
  *
  *   - `variadicSum` mimics a C variadic function whose first parameter is the fixed arity and the remainder is a heterogeneous `...` tail.
  *     The emitter lifts the trailing `args: Any*` via runtime-class dispatch (Int → JAVA_INT, Long → JAVA_LONG, Double → JAVA_DOUBLE,
  *     String → UTF-8 pointer, `Buffer[A]` → segment).
  *
  * The inspector must set `MethodSpec.hasVarargs = true` and exclude the varargs parameter from `params`.
  */
trait VariadicBindings extends Ffi:
    def variadicSum(count: Int, args: Any*)(using AllowUnsafe): Int
end VariadicBindings

object VariadicBindings extends Ffi.Config(library = "kyo_variadic")

/** Fixture exercising `Ffi.Config.headers` extraction.
  *
  * The companion declares `headers = Chunk("sys/test_header.h")` as a constructor parameter. The inspector must extract the `Chunk[String]`
  * literal from the TASTy AST, in particular, it must handle the `Block` wrapper that Scala 3 uses when non-trivial default args are
  * present, and resolve lifted locals back to their RHS.
  */
trait HeaderGatedBindings extends Ffi:
    def syscall(fd: Int)(using AllowUnsafe): Int
end HeaderGatedBindings

object HeaderGatedBindings extends Ffi.Config(library = "c", headers = Chunk("sys/test_header.h"))

/** Exercises all four Ffi.Config fields together in their kyo-idiomatic literal forms (scratchSize = Present(n),
  * headers = Chunk(...), symbols = Map(...), packedStructs = Set(...)), so the inspector reads all four fields off a
  * single companion object.
  */
trait AllConfigFieldsBindings extends Ffi:
    def echo(x: Int)(using AllowUnsafe): Int
end AllConfigFieldsBindings

object AllConfigFieldsBindings extends Ffi.Config(
        library = "kyo_all_fields",
        symbols = Map("echo" -> "kyo_echo"),
        packedStructs = Set("Packed"),
        scratchSize = Present(96 * 1024),
        headers = Chunk("stdint.h")
    )

/** Fixture exercising `Handle[A]` support.
  *
  * `FixtureHandle` and `FixtureOtherHandle` are phantom marker types. The inspector must produce `HandleT` references; the emitters must
  * marshal/unmarshal through the platform-specific pointer representations.
  */
class FixtureHandle
class FixtureOtherHandle

trait OpaqueBindings extends Ffi:
    def createHandle(value: Int)(using AllowUnsafe): Ffi.Handle[FixtureHandle]
    def readHandle(h: Ffi.Handle[FixtureHandle])(using AllowUnsafe): Int
    def destroyHandle(h: Ffi.Handle[FixtureHandle])(using AllowUnsafe): Unit
    def nullHandle()(using AllowUnsafe): kyo.Maybe[Ffi.Handle[FixtureHandle]]
end OpaqueBindings

object OpaqueBindings extends Ffi.Config(library = "kyo_fixture_opaque")

/** Fixture exercising structural enum detection.
  *
  * `FixtureColor` is a Scala 3 enum with a `value: Int` field and a companion `fromInt` method. The inspector structurally detects this
  * pattern and produces `EnumT` references and `EnumSpec` entries; the emitters must marshal via `.value` (param) and `fromInt(...)`
  * (return).
  */
enum FixtureColor(val value: Int):
    case Red   extends FixtureColor(0)
    case Green extends FixtureColor(1)
    case Blue  extends FixtureColor(2)
end FixtureColor

object FixtureColor:
    def fromInt(v: Int): FixtureColor = FixtureColor.values.find(_.value == v)
        .getOrElse(throw new IllegalArgumentException(s"Unknown: $v"))

trait EnumBindings extends Ffi:
    def getColor(index: Int)(using AllowUnsafe): FixtureColor
    def colorValue(c: FixtureColor)(using AllowUnsafe): Int
end EnumBindings

object EnumBindings extends Ffi.Config(library = "kyo_fixture_enum")

/** Fixture exercising struct fields with pointer-like types: opaque handles, strings, and function pointers.
  *
  * `StructWithPtrFields` contains an opaque handle field, a string field, and a function pointer field. The codegen must produce correct
  * read/write marshalling for each field type across all three platforms.
  */
case class StructWithPtrFields(handle: Ffi.Handle[FixtureHandle], label: String, callback: Int => Unit)

trait StructPtrFieldBindings extends Ffi:
    /** Write a struct with pointer fields to C. C reads the fields and returns the handle's value. */
    def readStructHandle(s: StructWithPtrFields)(using AllowUnsafe): Int
end StructPtrFieldBindings

object StructPtrFieldBindings extends Ffi.Config(library = "kyo_fixture_struct_ptr")

// -------------------------------------------------------------------------
// Negative fixtures: Borrowed[A] applied to unsupported inner types
// -------------------------------------------------------------------------

/** Negative fixture: `Borrowed[Int]`, Borrowed is only valid for String or Buffer, not primitives. */
trait BadBorrowedPrimitiveBindings extends Ffi:
    def badReturn()(using AllowUnsafe): Ffi.Borrowed[Int]
end BadBorrowedPrimitiveBindings

object BadBorrowedPrimitiveBindings extends Ffi.Config(library = "kyo_bad_borrowed_prim")

/** Negative fixture: `Borrowed[Buffer[Byte]]` with zero Int/Long parameters, size inference fails. */
trait BadBorrowedBufferNoSizeBindings extends Ffi:
    def badReturn(name: String)(using AllowUnsafe): Ffi.Borrowed[Buffer[Byte]]
end BadBorrowedBufferNoSizeBindings

object BadBorrowedBufferNoSizeBindings extends Ffi.Config(library = "kyo_bad_borrowed_nosize")

/** Negative fixture: `Borrowed[Buffer[Byte]]` with two Int/Long parameters, size inference is ambiguous. */
trait BadBorrowedBufferAmbiguousSizeBindings extends Ffi:
    def badReturn(offset: Int, length: Int)(using AllowUnsafe): Ffi.Borrowed[Buffer[Byte]]
end BadBorrowedBufferAmbiguousSizeBindings

object BadBorrowedBufferAmbiguousSizeBindings extends Ffi.Config(library = "kyo_bad_borrowed_ambig")

// -------------------------------------------------------------------------
// Negative fixtures: Enum structural detection failures
// -------------------------------------------------------------------------

/** Negative fixture: an enum used in a binding that lacks `val value: Int`. */
enum BadEnumNoValue:
    case A, B, C

object BadEnumNoValue:
    def fromInt(v: Int): BadEnumNoValue = BadEnumNoValue.values(v)

trait BadEnumNoValueBindings extends Ffi:
    def getColor(index: Int)(using AllowUnsafe): BadEnumNoValue
end BadEnumNoValueBindings

object BadEnumNoValueBindings extends Ffi.Config(library = "kyo_bad_enum_novalue")

/** Negative fixture: an enum with `val value: Int` but missing companion `fromInt`. */
enum BadEnumNoFromInt(val value: Int):
    case A extends BadEnumNoFromInt(0)
    case B extends BadEnumNoFromInt(1)

trait BadEnumNoFromIntBindings extends Ffi:
    def getColor(index: Int)(using AllowUnsafe): BadEnumNoFromInt
end BadEnumNoFromIntBindings

object BadEnumNoFromIntBindings extends Ffi.Config(library = "kyo_bad_enum_nofromint")

// -------------------------------------------------------------------------
// By-value struct return fixture (@Ffi.byValue)
// -------------------------------------------------------------------------

/** Fixture exercising the `@Ffi.byValue` annotation. A case-class return type is interpreted as a multi-value (C
  * out-param) return by default and as a by-value struct return (C `void f(S* out, ...args)`) when the method carries
  * `@Ffi.byValue`. The inspector must produce `ReturnShape.MultiValue` for the plain method and `ReturnShape.Struct`
  * for the annotated one; `byValueSingle` additionally proves a single-field struct is accepted by `@Ffi.byValue`
  * (a multi-value return requires at least two fields).
  */
case class ByValuePoint(x: Int, y: Int)
case class ByValueBox(v: Int)

trait ByValueBindings extends Ffi:
    /** Plain case-class return: multi-value (first field is the C return, the rest are out-params). */
    def plainPoint(seed: Int)(using AllowUnsafe): ByValuePoint

    /** By-value struct return: C `void make_point(ByValuePoint* out, int seed)`. */
    @Ffi.byValue
    def byValuePoint(seed: Int)(using AllowUnsafe): ByValuePoint

    /** By-value single-field struct return: a single field is allowed under `@Ffi.byValue`. */
    @Ffi.byValue
    def byValueSingle(v: Int)(using AllowUnsafe): ByValueBox
end ByValueBindings

object ByValueBindings extends Ffi.Config(library = "kyo_by_value")

// -------------------------------------------------------------------------
// Outcome return type fixture
// -------------------------------------------------------------------------

/** Fixture exercising `Ffi.Outcome[A]` return types for errno-aware C calls.
  *
  *   - `riskyOp` returns `Ffi.Outcome[Int]`, the codegen packs the C return value + captured errno into the opaque carrier and reads the C
  *     `int` at the width the `[Int]` argument names.
  *   - `safeOp` returns a plain `Int`, errno is not captured for it.
  */
trait WithErrorBindings extends Ffi:
    def riskyOp(x: Int)(using AllowUnsafe): Ffi.Outcome[Int]
    def safeOp(x: Int)(using AllowUnsafe): Int
end WithErrorBindings

object WithErrorBindings extends Ffi.Config(library = "kyo_with_error")

/** Regression guard for the arrow-form and tuple-form arms of extractStringPair.
  *
  * The `symbols` Map mixes one arrow entry (`"a" -> "b"`) and one tuple entry (`("c", "d")`).
  * FfiInspector.extractStringPair must extract BOTH from a single Map literal, exercising
  * the two disjoint-arity arms (arrow = one positional arg + arrowKey guard; tuple = two positional args)
  * in the same extraction call.
  */
trait MixedSymbolFormsBindings extends Ffi:
    def noop()(using AllowUnsafe): Unit
end MixedSymbolFormsBindings

object MixedSymbolFormsBindings extends Ffi.Config(
        library = "kyo_mixed_syms",
        symbols = Map("a" -> "b", ("c", "d"))
    )
