package kyo.kernel.internal

import kyo.kernel.<
import scala.quoted.*

object LiftMacro:

    /** The macro entry for the non-trivial lift shapes. On an object rather than in the
      * Implicits trait: a trait-member call in an inline body binds this-proxies at every
      * expansion, eight dead bytes per lift site even on the branches that never reach it.
      */
    inline def expand[A, S](v: A): A < S = ${ liftMacro[A, S]('v) }

    /** Emits the lift of a pure value into a computation, specialized by what the type can
      * prove statically: a bare cast when a value of the type can never need the nesting box
      * (Nothing, primitives and value classes, String, and final classes that are not Boxed,
      * since a final class admits no Boxed subtype), a direct Nested wrapper when the type is
      * statically pending (reachable through CanLift.unsafe.bypass, which exists to nest
      * deliberately), and the runtime Boxed test for everything else. Traits, abstract and
      * non-final classes, opaques, and the top types all stay on the runtime test: a trait
      * value can be a Nested (which implements Product), an Arrow-typed value can be a fused
      * suspension, and an opaque's underlying can admit computations (Loop.Outcome carries
      * them). The nesting discipline itself lives in CanLift; this macro only chooses the
      * emission.
      */
    def liftMacro[A: Type, S: Type](v: Expr[A])(using Quotes): Expr[A < S] =
        import quotes.reflect.*

        enum Mode derives CanEqual:
            case Cast, Nested, DefaultLift

        val tpe  = TypeRepr.of[A].dealias
        val wide = tpe.widen.dealias
        val sym  = wide.typeSymbol

        def isNothing = tpe =:= TypeRepr.of[Nothing]
        def isPending = tpe <:< TypeRepr.of[Any < Nothing]
        def isValue   = wide <:< TypeRepr.of[AnyVal] || wide <:< TypeRepr.of[String]
        def isSafeFinalClass =
            sym.isClassDef && sym.flags.is(Flags.Final) && !sym.flags.is(Flags.Trait) &&
                !(wide <:< TypeRepr.of[Boxed])

        val mode =
            if isNothing then Mode.Cast
            else if isPending then Mode.Nested
            else if isValue || isSafeFinalClass then Mode.Cast
            else Mode.DefaultLift

        mode match
            case Mode.Cast        => '{ $v.asInstanceOf[A < S] }
            case Mode.Nested      => '{ Nested($v).asInstanceOf[A < S] }
            case Mode.DefaultLift => '{ defaultLift[A, S]($v) }
        end match
    end liftMacro

    final def defaultLift[A, S](v: A): A < S =
        Nested.lift(v)

    def abortCastUnitMacro[S1: Type, S2: Type](v: Expr[Unit < S1])(using Quotes): Expr[Unit < S2] =
        import quotes.reflect.*
        val source = TypeRepr.of[S1].show
        report.errorAndAbort(
            s"""Cannot lift `Unit < ${source}` to the expected type (`Unit < ?`).
               |This may be due to an effect type mismatch.
               |Consider removing or adjusting the type constraint on the left-hand side.
               |More info : https://github.com/getkyo/kyo/issues/903""".stripMargin
        )
    end abortCastUnitMacro

end LiftMacro
