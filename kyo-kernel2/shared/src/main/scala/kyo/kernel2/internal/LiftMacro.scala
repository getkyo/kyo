package kyo.kernel2.internal

import kyo.kernel2.*
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
            case Mode.Cast                      => '{ $v.asInstanceOf[A < S] }
            case Mode.Nested | Mode.DefaultLift => '{ LiftMacro.defaultLift[A, S]($v) }
        end match
    end liftMacro

    final def defaultLift[A, S](v: A): A < S =
        `<`.liftSlow(v).asInstanceOf[A < S]

end LiftMacro
