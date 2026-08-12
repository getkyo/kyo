package kyo.kernel.internal

import kyo.kernel.<
import scala.quoted.*

object LiftMacro:

    /** The macro entry for the non-trivial lift shapes. On an object rather than in the
      * Implicits trait: a trait-member call in an inline body binds this-proxies at every
      * expansion, eight dead bytes per lift site even on the branches that never reach it.
      */
    inline def expand[A, S](v: A): A < S = ${ liftMacro[A, S]('v) }

    /** The whole lift decision for the non-trivial shapes, gate and emission in one place.
      *
      * Rejections: a statically pending type aborts with the flatten guidance (implicit
      * nesting is always a mistake; deliberate nesting goes through Kyo.lift, which uses the
      * ungated internal path), and a kyo module singleton aborts with the module message.
      *
      * Emission: a bare cast when a value of the type can never need the nesting box
      * (Nothing, value types, String, non-module singletons, and final classes that are not
      * Boxed, since a final class admits no Boxed subtype), and the runtime Boxed test for
      * everything else. Traits, abstract and non-final classes, opaques, and the top types
      * all stay on the runtime test: a trait value can be a Nested (which implements
      * Product), an Arrow-typed value can be a fused suspension, and an opaque's underlying
      * can admit computations (Loop.Outcome carries them).
      */
    def liftMacro[A: Type, S: Type](v: Expr[A])(using Quotes): Expr[A < S] =
        import quotes.reflect.*

        val tpe  = TypeRepr.of[A].dealias
        val wide = tpe.widen.dealias
        val sym  = wide.typeSymbol

        def isNothing = tpe =:= TypeRepr.of[Nothing]
        def isPending = tpe <:< TypeRepr.of[Any < Nothing]
        def isModule  = sym.fullName.startsWith("kyo.") && sym.flags.is(Flags.Module) && !sym.flags.is(Flags.Case)
        def isValue   = wide <:< TypeRepr.of[AnyVal] || wide <:< TypeRepr.of[String]
        def isSafeFinalClass =
            sym.isClassDef && sym.flags.is(Flags.Final) && !sym.flags.is(Flags.Trait) &&
                !(wide <:< TypeRepr.of[Boxed])

        if isNothing then '{ $v.asInstanceOf[A < S] }
        else if isPending then
            report.errorAndAbort(
                s"""Type '${tpe.show}' may contain a nested effect computation.
                   |This usually means a value of type `X < S1 < S2` where a plain `X < S` is expected,
                   |typically from type inference nesting effect computations instead of merging them.
                   |Call `.flatten` to merge the nested effects, or split the expression into
                   |smaller statements so the effect rows unify.""".stripMargin,
                Position.ofMacroExpansion
            )
        else if isModule then
            report.errorAndAbort(s"Cannot lift '${sym.fullName}' to a '${sym.name} < S'", Position.ofMacroExpansion)
        else if isValue || isSafeFinalClass then '{ $v.asInstanceOf[A < S] } else '{ defaultLift[A, S]($v) }
        end if
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
