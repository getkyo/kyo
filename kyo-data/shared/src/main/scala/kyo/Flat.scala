package kyo

import scala.quoted.*

/** A compile-time guarantee that a parametrized opaque type with internal flat encoding doesn't contain instances of itself.
  *
  * When designing opaque types with flat internal encodings like `A | Container[A]` (union types), runtime discrimination between the cases
  * requires that `A` isn't itself another instance of the opaque type. Otherwise, type discrimination would confuse nested instances with
  * direct values, breaking the semantics of operations on the type at runtime.
  *
  * For example, given `opaque type Box[A] = A | Container[A]`, allowing `A` to be another `Box[A]` would make it impossible to reliably
  * distinguish between a direct value and a boxed value through runtime checks.
  *
  * `Flat[A]` enforces this constraint at compile-time through a macro that recursively checks the structure of types:
  *   - For union and intersection types, each component is checked
  *   - For other types, it verifies they have their own `Flat` evidence or are concrete class types, which excludes opaque types
  *   - It fails compilation with a helpful error message when it detects potential nesting issues
  *
  * This ensures the integrity of the flat encoding pattern with union types without runtime overhead.
  *
  * @tparam A
  *   the type to verify does not contain unwanted opaque type nesting
  */
opaque type Flat[A] = Null

object Flat:
    /** Provides unsafe methods to bypass Flat type checking. */
    object unsafe:
        /** Unconditionally provides Flat evidence for any type.
          *
          * Warning: Use only when you can guarantee the type doesn't contain unwanted opaque type nesting. Useful for custom types where
          * you control the implementation and can ensure no problematic nesting.
          */
        inline given bypass[A]: Flat[A] = null
    end unsafe

    /** Derives Flat evidence for type A via compile-time checks.
      *
      * This macro recursively examines the structure of the type to ensure it cannot contain nested instances that would violate the flat
      * encoding pattern.
      */
    inline given derive[A]: Flat[A] = FlatMacro.derive

end Flat

private object FlatMacro:

    inline def derive[A]: Flat[A] = ${ macroImpl[A] }

    def macroImpl[A: Type](using Quotes): Expr[Flat[A]] =
        import quotes.reflect.*

        val t = TypeRepr.of[A].dealias

        def code(str: String) =
            s"${scala.Console.YELLOW}'$str'${scala.Console.RESET}"

        def isAny(t: TypeRepr) =
            t.typeSymbol eq TypeRepr.of[Any].typeSymbol

        def isConcrete(t: TypeRepr) =
            t.typeSymbol.isClassDef

        def canDerive(t: TypeRepr): Boolean =
            t.asType match
                case '[t] =>
                    Expr.summon[Flat[t]].isDefined || Expr.summon[Tag.Full[t]].isDefined

        def check(t: TypeRepr): Unit =
            t match
                case OrType(a, b) =>
                    check(a)
                    check(b)
                case AndType(a, b) =>
                    check(a)
                    check(b)
                case _ =>
                    if isAny(t) || (!isConcrete(t.dealias) && !canDerive(t)) then
                        report.errorAndAbort(
                            s"Cannot prove ${code(t.show)} isn't nested. " +
                                s"This error can be reported an unsupported pending effect is passed to a method. " +
                                s"If that's not the case, provide an implicit evidence ${code(s"kyo.Flat[${t.show}]")}."
                        )

        check(t)
        '{ Flat.unsafe.bypass[A] }
    end macroImpl
end FlatMacro
