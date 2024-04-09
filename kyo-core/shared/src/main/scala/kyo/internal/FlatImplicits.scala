package kyo.internal

import kyo.*
import scala.quoted.*

trait FlatImplicits0:
    inline given infer[T]: Flat[T] =
        ${ FlatImplicits.inferMacro[T] }

trait FlatImplicits1 extends FlatImplicits0:
    given product[T <: Product]: Flat[T] =
        Flat.unsafe.bypass[T]

trait FlatImplicits extends FlatImplicits1:
    given anyVal[T <: AnyVal]: Flat[T] =
        Flat.unsafe.bypass[T]

object FlatImplicits:

    def inferMacro[T: Type](using Quotes): Expr[Flat[T]] =
        import quotes.reflect.*

        val t = TypeRepr.of[T].dealias

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

        def canDerive(t: TypeRepr): Boolean =
            t.dealias match
                case OrType(left, right) =>
                    canDerive(left) && canDerive(right)
                case tpe =>
                    tpe.asType match
                        case '[nt] => Expr.summon[Flat[nt]] match
                                case Some(_) => true
                                case None    => false

        def isAny(t: TypeRepr) =
            t.typeSymbol eq TypeRepr.of[Any].typeSymbol

        def isConcrete(t: TypeRepr) =
            t.typeSymbol.isClassDef

        def check(t: TypeRepr): Expr[Flat[T]] =
            if isAny(t) || !isConcrete(t.dealias) then
                canDerive(t) match
                    case true =>
                        '{ Flat.unsafe.bypass[T] }
                    case false =>
                        fail(
                            s"Cannot prove ${code(print(t))} isn't nested. Provide an implicit evidence ${code(s"kyo.Flat[${print(t)}]")}."
                        )
            else
                '{ Flat.unsafe.bypass[T] }

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
            case Kyo(nt, s) =>
                check(nt)
            case t =>
                check(t)
        end match
    end inferMacro
end FlatImplicits
