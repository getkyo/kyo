package kyo.internal

import kyo.*
import scala.quoted.*

/** Separate macro object for Schema.derived to avoid circular class loading.
  *
  * FocusMacro references Focus.Select in method signatures, which causes NoClassDefFoundError when the JVM loads FocusMacro during
  * Schema.scala's own compilation. This thin wrapper lives in its own class file and delegates to FocusMacro.derivedImpl.
  */
object SchemaDerivedMacro:

    def derivedImpl[A: Type](using Quotes): Expr[Schema[A]] =
        FocusMacro.derivedImpl[A]

end SchemaDerivedMacro
