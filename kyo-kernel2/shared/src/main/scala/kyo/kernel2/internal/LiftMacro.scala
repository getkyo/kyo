package kyo.kernel2.internal

import kyo.kernel2.*
import kyo.kernel2.internal.Kyo
import scala.quoted.*

object LiftMacro:

    def liftMacro[A: Type, S: Type](v: Expr[A])(using Quotes): Expr[A < S] =
        import quotes.reflect.*

        val tpe = TypeRepr.of[A].dealias
        val sym = tpe.typeSymbol

        def isNothing  = tpe =:= TypeRepr.of[Nothing]
        def isPending  = tpe <:< TypeRepr.of[Any < Nothing]
        def isConcrete = sym.isClassDef
        def isOpaque   = sym.flags.is(Flags.Opaque)

        // the pending check comes first: the pending type is opaque, so the concrete/opaque
        // cast would otherwise claim a value that must enter as data (the unsafe.bypass route
        // is the one way a statically-pending type reaches here)
        if isNothing then '{ $v.asInstanceOf[A < S] }
        else if isPending then '{ defaultLift[A, S]($v) }
        else if isConcrete || isOpaque then '{ $v.asInstanceOf[A < S] } else '{ defaultLift[A, S]($v) }
    end liftMacro

    /** The pending type's runtime lift: a value that is a computation enters as data (a [[Kyo.Nested]] box), anything else casts. All
      * lifting funnels through here; the macro emits it when the type does not settle the case statically, and the kernel's raw
      * re-entry sites call it directly on their erased currency.
      */
    inline def defaultLift[A, S](inline v: A): A < S =
        v match
            case v: Kyo[?, ?]     => Kyo.Nested(v).asInstanceOf[A < S]
            case v: Kyo.Nested[?] => Kyo.Nested(v).asInstanceOf[A < S]
            case v                => v.asInstanceOf[A < S]

end LiftMacro
