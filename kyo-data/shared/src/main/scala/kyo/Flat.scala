package kyo

import scala.quoted.*

opaque type Flat[A] = Null

object Flat:
    object unsafe:
        inline given bypass[A]: Flat[A] = null

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
                    Expr.summon[Flat[t]].isDefined || Expr.summon[Tag[t]].isDefined

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
