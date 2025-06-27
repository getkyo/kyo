package kyo.kernel.internal

import kyo.kernel.<
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.quoted.*

object Lift:

    inline def liftUnit[S1, S2](inline v: Unit < S1): Unit < S2 = ${ liftUnitImpl[S1, S2]('v) }

    def liftUnitImpl[S1: Type, S2: Type](v: Expr[Unit < S1])(using quotes: Quotes): Expr[Unit < S2] =
        import quotes.reflect.*
        val source = TypeRepr.of[S1].show
        report.errorAndAbort(
            s"""Cannot lift `Unit < ${source}` to the expected type (`Unit < ?`).
               |This may be due to an effect type mismatch.
               |Consider removing or adjusting the type constraint on the left-hand side.
               |More info : https://github.com/getkyo/kyo/issues/903""".stripMargin
        )
    end liftUnitImpl

    inline def lift[A: WeakFlat, S](inline v: A): A < S = ${ liftMacro[A, S]('v) }

    def liftMacro[A: Type, S: Type](v: Expr[A])(using Quotes): Expr[A < S] =
        import quotes.reflect.*

        val tpe = TypeRepr.of[A].dealias
        val sym = tpe.typeSymbol

        def isTrivialType: Boolean =
            tpe match
                case _: ConstantType => true
                case _ =>
                    sym.fullName match
                        case "scala.Unit" => true
                        case "scala.Int" | "scala.Long" | "scala.Double"
                            | "scala.Float" | "scala.Short" | "scala.Byte"
                            | "scala.Char" | "scala.Boolean" => true
                        case _ => false

        if isTrivialType then
            '{ $v.asInstanceOf[A < S] }
        else if sym.flags.is(Flags.Module) && sym.fullName.startsWith("kyo.") then
            report.error(s"Cannot lift object of type `${sym.fullName}.type` into `${sym.name}.type < S`")
            '{ ??? }
        else
            '{
                $v match
                    case kyo: Kyo[?, ?] => Nested(kyo)
                    case _              => $v.asInstanceOf[A < S]
            }
        end if
    end liftMacro
end Lift
