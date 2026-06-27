package kyo.internal

import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.Frame
import kyo.Present
import kyo.Schema
import scala.compiletime.erasedValue

// Per-field serialization bridge for macro-generated derivation code.
//
// `Schema.serializeWrite` / `serializeRead` are `@publicInBinary private[kyo]`: `@publicInBinary`
// relaxes the binary boundary but not the source-level `private[kyo]` access check at the
// generated-code typer phase, so derivation code generated at a user `derives Schema` site cannot
// call them directly. These `inline` bridges live in `package kyo.internal` (inside `kyo`, so the
// `private[kyo]` access still succeeds) and stay out of the user-facing `kyo` namespace; the macro
// emits the qualified `kyo.internal.writeField` / `kyo.internal.readField`, and the `inline s`
// parameter substitutes the field's concrete `summonInline[Schema[ft]]` at the call site.
//
// The macro stays fully generic: it emits one `writeField`/`readField` per field and never inspects
// the field type. The primitive fast-path lives HERE, in a hand-written `inline` match on the field
// type `A`, resolved at the generated-code typer phase. For the closed set of JVM primitives it calls
// the `Writer`/`Reader` typed method directly (the exact encoding the primitive givens use), so the
// value never crosses the erased `serializeWrite(Object, _)` / `serializeRead(): Object` boundary and
// never boxes. Every other type (String, collections, Option/Maybe, nested products, sealed traits,
// user containers) is a reference and dispatches through the field schema's own `serializeWrite` /
// `serializeRead`, monomorphically, with no shared dispatcher, runtime field walk, or `Function2`
// indirection. Structural transforms (drop / rename / discriminator / computed) are applied inside the
// field schema's own `serializeWrite` / `serializeRead` (see `Schema.init`), so this direct call stays
// correct for a transformed field schema without any check here. Specialization lives in this
// hand-written helper over a fixed language-level set, never in the macro.
inline def writeField[A](inline s: Schema[A], a: A, w: Writer): Unit =
    inline erasedValue[A] match
        case _: Boolean => w.boolean(a.asInstanceOf[Boolean])
        case _: Int     => w.int(a.asInstanceOf[Int])
        case _: Long    => w.long(a.asInstanceOf[Long])
        case _: Double  => w.double(a.asInstanceOf[Double])
        case _: Float   => w.float(a.asInstanceOf[Float])
        case _: Short   => w.short(a.asInstanceOf[Short])
        case _: Byte    => w.byte(a.asInstanceOf[Byte])
        case _: Char    => w.char(a.asInstanceOf[Char])
        case _          => s.serializeWrite(a, w)

inline def readField[A](inline s: Schema[A], r: Reader): A =
    inline erasedValue[A] match
        case _: Boolean => r.boolean().asInstanceOf[A]
        case _: Int     => r.int().asInstanceOf[A]
        case _: Long    => r.long().asInstanceOf[A]
        case _: Double  => r.double().asInstanceOf[A]
        case _: Float   => r.float().asInstanceOf[A]
        case _: Short   => r.short().asInstanceOf[A]
        case _: Byte    => r.byte().asInstanceOf[A]
        case _: Char    => r.char().asInstanceOf[A]
        case _          => s.serializeRead(r)

inline def absentDefaultSeed[A](inline s: Schema[A]): A =
    s.absentDefaultValue match
        case Present(value) => value
        case _              => null.asInstanceOf[A]

inline def absentDefaultMask[A](inline s: Schema[A], bit: Long): Long =
    s.absentDefaultValue match
        case Present(_) => bit
        case _          => 0L
