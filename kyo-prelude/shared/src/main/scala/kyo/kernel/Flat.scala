package kyo.kernel

import kyo.Tag
import scala.quoted.*

opaque type Flat[A] = Null

object Flat:
    object unsafe:
        inline given bypass[A]: Flat[A] = null
    end unsafe

    inline given infer[A]: Flat[A] = FlatMacro.infer
end Flat

private object FlatMacro:

    inline def infer[A]: Flat[A] = ${ macroImpl[A] }

    def macroImpl[A: Type](using Quotes): Expr[Flat[A]] =
        import quotes.reflect.*

        val t = TypeRepr.of[A].dealias

        object Kyo:
            def unapply(tpe: TypeRepr): Option[(TypeRepr, TypeRepr)] =
                tpe match
                    case AppliedType(_, List(t, u))
                        if (tpe.typeSymbol eq TypeRepr.of[<].typeSymbol) =>
                        Some((t.dealias, u.dealias))
                    case _ => None
        end Kyo

        def code(str: String) =
            s"${scala.Console.YELLOW}'$str'${scala.Console.RESET}"

        def print(t: TypeRepr): String =
            t match
                case Kyo(t, s) =>
                    s"${print(t)} < ${print(s)}"
                case _ => t.show

        def fail(msg: String) =
            report.errorAndAbort(s"Method doesn't accept nested Kyo computations.\n$msg")

        def isAny(t: TypeRepr) =
            t.typeSymbol eq TypeRepr.of[Any].typeSymbol

        def isConcrete(t: TypeRepr) =
            t.typeSymbol.isClassDef

        def hasTag(t: TypeRepr): Boolean =
            t.asType match
                case '[t] =>
                    Expr.summon[Tag.Full[t]].isDefined

        def check(t: TypeRepr): Unit =
            t match
                case OrType(a, b) =>
                    check(a)
                    check(b)
                case AndType(a, b) =>
                    check(a)
                    check(b)
                case _ =>
                    if isAny(t) || (!isConcrete(t.dealias) && !hasTag(t)) then
                        fail(
                            s"Cannot prove ${code(print(t))} isn't nested. " +
                                s"This error can be reported an unsupported pending effect is passed to a method. " +
                                s"If that's not the case, provide an implicit evidence ${code(s"kyo.Flat[${print(t)}]")}."
                        )

        t match
            case Kyo(Kyo(nt, s1), s2) =>
                val mismatch =
                    if print(s1) != print(s2) then
                        s"\nPossible pending effects mismatch: Expected ${code(print(s2))}, found ${code(print(s1))}."
                    else
                        ""
                fail(
                    s"Detected: ${code(print(t))}. Consider using ${code("flatten")} to resolve. " + mismatch
                )
            case t =>
                check(t)
        end match
        '{ Flat.unsafe.bypass[A] }
    end macroImpl
end FlatMacro
