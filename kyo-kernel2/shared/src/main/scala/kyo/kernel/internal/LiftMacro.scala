package kyo.kernel.internal

import kyo.kernel.<
import kyo.kernel.Kyo
import kyo.kernel.Nested
import scala.quoted.*

object LiftMacro:

    def liftMacro[A: Type, S: Type](v: Expr[A])(using Quotes): Expr[A < S] =
        import quotes.reflect.*

        enum Mode derives CanEqual:
            case Cast, Boxed, DefaultLift

        val sourceTpe = TypeRepr.of[A]
        val tpe       = sourceTpe.dealias
        val sym       = tpe.typeSymbol

        def isNothing = tpe =:= TypeRepr.of[Nothing]
        def isPending = tpe <:< TypeRepr.of[Any < Nothing]
        def isConcrete =
            sym.isClassDef && !(tpe <:< TypeRepr.of[Kyo[?, ?]]) && !(tpe <:< TypeRepr.of[Nested[?]])
        def isOpaque = sym.flags.is(Flags.Opaque)

        val mode =
            if isNothing then Mode.Cast
            else if isPending then Mode.Boxed
            else if isConcrete || isOpaque then Mode.Cast
            else Mode.DefaultLift

        mode match
            case Mode.Cast        => '{ $v.asInstanceOf[A < S] }
            case Mode.Boxed       => '{ Nested($v).asInstanceOf[A < S] }
            case Mode.DefaultLift => '{ defaultLift($v) }
        end match
    end liftMacro

    final def defaultLift[A, S](v: A): A < S =
        Nested.lift(v)

end LiftMacro
