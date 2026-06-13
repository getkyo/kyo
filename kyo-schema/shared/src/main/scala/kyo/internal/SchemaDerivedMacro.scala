package kyo.internal

import kyo.*
import scala.quoted.*

/** Schema derivation entry point.
  *
  * Walks the target type's structural shape (case class, sealed trait, or rejects everything
  * else) and emits a `new Schema[A]` literal whose nested field/variant Schemas are resolved
  * at macro-expansion time via `Expr.summon[Schema[t]]`. Container field types (Chunk, List,
  * Maybe, Option, etc.) whose Schema givens carry a `using Frame` constraint that is not
  * satisfied at the `derives Schema` call site fall back to `buildContainerSchemaOpt`, which
  * synthesizes the appropriate container Schema from the resolved element Schema. Sealed
  * traits route through `generateSealedTraitSchema`, which builds variant resolvers that also
  * combine `Expr.summon[Schema[v]]` with `buildContainerSchemaOpt` for variants whose givens
  * cannot be summoned directly.
  *
  * The optional flag on each emitted `Structure.Field` is computed at runtime by inspecting
  * the resolved Schema's structure: a `Structure.Type.Optional(_, _, _)` shape sets
  * `optional = true`. The macro never inspects the field's type symbol; the type-shape
  * dispatch on the resolved structure keeps the zero-specialization invariant intact while
  * preserving the wire-observable `optional` flag for Maybe/Option fields.
  *
  * The default value on each field summons the field's `Tag[t]` via
  * `scala.compiletime.summonInline[Tag[t]]` and passes it to `Structure.Value.primitive`,
  * so the primitive default encodes to the matching Value variant (Integer / Boolean /
  * Decimal / etc.) rather than collapsing to Str(toString) under `Tag[Any]`.
  *
  * This file is kept as a thin class-file boundary so that the macro call in Schema.scala
  * (`inline given derived`) does not create a cyclic compile-time dependency. The actual
  * implementation (which references Schema types in quoted output) lives in FocusMacro,
  * which Schema.scala already depends on for other macro calls. See FocusMacro.derivedImpl
  * for the full implementation.
  */
object SchemaDerivedMacro:

    def derivedImpl[A: Type](using Quotes): Expr[Schema[A]] =
        FocusMacro.derivedImpl[A]

end SchemaDerivedMacro
