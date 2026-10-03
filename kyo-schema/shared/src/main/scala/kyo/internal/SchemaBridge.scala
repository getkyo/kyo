package kyo.internal

import kyo.Codec.Reader
import kyo.Codec.Writer
import kyo.ConstructorRejectedException
import kyo.Frame
import kyo.Present
import kyo.Result
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

// An absent optional field is left off the wire, except in a record read back by position, where it is written as null so the later
// positions stay in place; null decodes to the absent value.
def writeAbsentField(nameBytes: Array[Byte], fieldId: Int, w: Writer): Unit =
    if w.writesEveryField then
        w.fieldBytes(nameBytes, fieldId)
        w.nil()

// An absent optional field whose default is present is written as null wherever a format can: left off, it would read back as the
// default. A format with no null writes nothing, and its reader takes the missing field as absent (see
// `Codec.Reader.missingOptionalIsAbsent`).
def writeAbsentDefaultedField(nameBytes: Array[Byte], fieldId: Int, w: Writer, defaultIsAbsent: Boolean): Unit =
    if !defaultIsAbsent || w.writesEveryField then
        w.fieldBytes(nameBytes, fieldId)
        w.nil()

// The value an optional field with a default starts from before its record is read.
def defaultedOptionalSeed[A](r: Reader, default: A, empty: A): A =
    if r.missingOptionalIsAbsent then empty else default

// Smart-constructor fold for `Schema.derivedVia`-generated decoders.
//
// The generated read body decodes every field exactly as a plain product does, then hands the
// constructor's outcome here instead of calling the primary constructor. A rejection becomes a
// `ConstructorRejectedException`, which is a `DecodeException`, so it surfaces through
// `Schema.decode`'s `Result.catching[DecodeException]` as a `Result.Failure` like any other decode
// failure instead of escaping as a raw throw. The error branch is matched before unwrapping, and the
// success branch goes through `getOrThrow` so a success value that is itself a `Result.Error` (kyo
// nests those as `SuccessError`) unnests correctly rather than being read as a rejection.
def constructedOrThrow[A](outcome: Result[Any, A], typeName: String)(using Frame): A =
    outcome match
        case error: Result.Error[Any] @unchecked =>
            val rejection: String | Throwable = error.failureOrPanic match
                case throwable: Throwable => throwable
                case other                => String.valueOf(other)
            throw ConstructorRejectedException(Seq.empty, typeName, rejection)
        case success =>
            success.asInstanceOf[Result[Nothing, A]].getOrThrow

// A generated read body's required-field check, kept out of line so every derived schema carries one call instead of the check.
// A required field neither seen, dropped nor defaulted when absent is missing, reported by the first such field's name.
def checkRequired(
    seen: Long,
    r: Reader,
    n: Int,
    absentDefaultableMask: Long,
    requiredMask: Long,
    nameBytes: Array[Array[Byte]]
): Unit =
    val combined = seen | r.droppedFieldsMask(n) | r.absentDefaultedFieldsMask(n, absentDefaultableMask)
    if (combined & requiredMask) != requiredMask then
        val missing = java.lang.Long.numberOfTrailingZeros((~combined) & requiredMask)
        throw kyo.MissingFieldException(Seq.empty, new String(nameBytes(missing), java.nio.charset.StandardCharsets.UTF_8))(using r.frame)
    end if
end checkRequired

// The field a generated read body was reading when a decode failure escaped it. The body records the field's index while it reads the
// value and -1 otherwise, so a failure raised between fields (a malformed separator, an unknown field under denyUnknownFields) is left
// as the record's own and gains no segment.
def prependFieldPath(e: kyo.DecodeException, current: Int, nameBytes: Array[Array[Byte]]): kyo.DecodeException =
    if current < 0 then e
    else e.prependPath(new String(nameBytes(current), java.nio.charset.StandardCharsets.UTF_8))

// Reads one element of a sequence; a decode failure inside it gains the element's index. The index becomes a string only on failure.
def readElementAt[A](s: Schema[A], r: Reader, index: Int): A =
    try s.serializeRead(r)
    catch case e: kyo.DecodeException => throw e.prependPath(index.toString)

// Reads the value of a map entry keyed by `key`, or one side of an entry written as a `{key, value}` pair at `index`.
def readEntryAt[A](s: Schema[A], r: Reader, key: String): A =
    try s.serializeRead(r)
    catch case e: kyo.DecodeException => throw e.prependPath(key)

def readPairSideAt[A](s: Schema[A], r: Reader, index: Int, side: String): A =
    try s.serializeRead(r)
    catch case e: kyo.DecodeException => throw e.prependPath(side).prependPath(index.toString)

/** The variant a sum decodes input no other variant matches into, from `@catchAll()` or `catchAll`.
  *
  * The variant has `arity` fields (one or two); `tagIndex` is the position of its first `String` field, else of its first `Int` or
  * `Long` field (`numericTag`), or -1. A numeric tag field serves only a sum with numbered variants, a `String` one only a sum with
  * named variants. Which field takes the tag and which the unmatched input depends on the representation
  * (`SchemaSerializer.catchAllSlots`), so one carrier serves every representation the variant's shape fits. `construct` builds the
  * variant from one captured value per field, in declaration order, each read through the field's own schema. `onFailure` also
  * routes a known tag whose variant fails to decode to it. Public in `kyo.internal` because the sum derivation emits it at the
  * user's site.
  */
final case class CatchAll(
    variant: String,
    arity: Int,
    tagIndex: Int,
    numericTag: Boolean,
    onFailure: Boolean,
    construct: kyo.Chunk[kyo.Structure.Value] => Any
)

// Reads one field of a catch-all variant from the value the sum captured, through the field's own schema.
def readCaptured[A](s: Schema[A], value: kyo.Structure.Value): A =
    s.serializeRead(new StructureValueReader(value)(using Frame.internal))

inline def absentDefaultSeed[A](inline s: Schema[A]): A =
    s.absentDefaultValue match
        case Present(value) => value
        case _              => null.asInstanceOf[A]

inline def absentDefaultMask[A](inline s: Schema[A], bit: Long): Long =
    s.absentDefaultValue match
        case Present(_) => bit
        case _          => 0L
