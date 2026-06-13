package kyo.internal

import kyo.*
import scala.quoted.*

/** Schema derivation entry point.
  *
  * Delegates to [[FocusMacro.derivedImpl]] which emits a fully generic Schema whose nested
  * field/variant Schemas are resolved at the inline-expansion phase via
  * `scala.compiletime.summonInline[Schema[ft]]`. The macro never enumerates container or primitive
  * type symbols: containers, primitives, and user-defined parametric Schemas all resolve through the
  * same `summonInline` path.
  *
  * This file is kept as a thin class-file boundary so the macro call in Schema.scala
  * (`inline given derived`) does not create a cyclic compile-time dependency.
  */
object SchemaDerivedMacro:

    def derivedImpl[A: Type](using Quotes): Expr[Schema[A]] =
        FocusMacro.derivedImpl[A]

end SchemaDerivedMacro
