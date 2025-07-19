package kyo.kernel.internal

import kyo.<
import scala.quoted.*

object LiftMacro:

    def liftMacro[A: Type, S: Type](v: Expr[A])(using Quotes): Expr[A < S] =
        import quotes.reflect.*

        enum Mode derives CanEqual:
            case Cast, Nested, DefaultLift

        val sourceTpe = TypeRepr.of[A]
        val tpe       = sourceTpe.dealias
        val sym       = tpe.typeSymbol

        def isNothing  = tpe =:= TypeRepr.of[Nothing]
        def isPending  = tpe <:< TypeRepr.of[Any < Nothing]
        def isConcrete = sym.isClassDef
        def isOpaque   = sym.flags.is(Flags.Opaque)

        val mode =
            if isNothing then Mode.Cast
            else if isPending then Mode.Nested
            else if isConcrete || isOpaque then Mode.Cast
            else Mode.DefaultLift

        mode match
            case Mode.Cast        => '{ $v.asInstanceOf[A < S] }
            case Mode.Nested      => '{ Nested($v) }
            case Mode.DefaultLift => '{ defaultLift($v) }
        end match
    end liftMacro

    final def defaultLift[A, S](v: A): A < S =
        v match
            case kyo: Kyo[?, ?] => Nested(kyo)
            case _              => v.asInstanceOf[A < S]

end LiftMacro
