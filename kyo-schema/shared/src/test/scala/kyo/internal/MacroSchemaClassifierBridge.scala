package kyo.internal

import scala.quoted.*

/** Compile-time bridge for MacroSchemaClassifierTest.
  *
  * Scala 3 prohibits calling a macro implementation from the same file where it is defined.
  * This file provides the `classifyField[T]` inline entrypoint separately from the test class.
  */
object MacroSchemaClassifierBridge:

    /** Runs fieldKindFor at compile time for type T and returns a string at runtime.
      *
      * Used by MacroSchemaClassifierTest to verify classifier output without depending on
      * runtime Schema instances.
      */
    inline def classifyField[T]: String = ${ classifyFieldImpl[T] }

    private def classifyFieldImpl[T: Type](using Quotes): scala.quoted.Expr[String] =
        import quotes.reflect.*
        val kind = MacroUtils.MacroSchemaClassifier.fieldKindFor(TypeRepr.of[T])
        scala.quoted.Expr(kind.toString)
    end classifyFieldImpl

end MacroSchemaClassifierBridge
