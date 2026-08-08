package kyo.kernel2.internal

import kyo.kernel2.*
import kyo.kernel2.internal.Kyo
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
            case Mode.Nested | Mode.DefaultLift => '{ defaultLift[A, S]($v) }
        end match
    end liftMacro

    /** The pending type's runtime lift: a value that is a computation enters as data (a [[Kyo.Nested]] box), anything else casts. All
      * lifting funnels through here; the macro emits it when the type does not settle the case statically, and the kernel's raw
      * re-entry sites call it directly on their erased currency.
      */
    final def defaultLift[A, S](v: A): A < S =
        v match
            case v: Kyo[?, ?]     => Kyo.Nested(v).asInstanceOf[A < S]
            case v: Kyo.Nested[?] => Kyo.Nested(v).asInstanceOf[A < S]
            case v                => v.asInstanceOf[A < S]

end LiftMacro
