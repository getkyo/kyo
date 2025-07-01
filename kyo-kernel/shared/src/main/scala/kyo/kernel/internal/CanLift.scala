package kyo.kernel.internal

import kyo.<
import scala.annotation.implicitNotFound
import scala.quoted.*
import scala.util.NotGiven

/** CanLift is a "soft" constraint that indicates a type should not contain nested effect computations (A < S), or A is not a module from
  * kyo (like Abort.type).
  *
  * This constraint helps:
  *   - prevent accidental nesting of effects that would require flattening, but cannot be strictly enforced in all generic contexts,
  *   - prevent calling combinators from (A < S) on modules, like Abort.foldAbort.
  *
  * @tparam A
  *   The type to check for nested effects
  */
@implicitNotFound("""
Type '${A}' may contain a nested effect computation.
This usually means you have a value of type `X < S1 < S2` (i.e. `(X < S1) < S2`) where a plain value `X < S` is expected.

This often happens due to *type inference*: some effect computations are nested when chaining operations, and Scala infers a value with nested effects instead of merging them.

To fix this, you can:

1. Call `.flatten` to merge the nested effects:
    val x: (Int < S1) < S2 = ...
    val y: Int < (S1 & S2) = x.flatten


   This collapses the nested effect layers into a single computation with a combined effect set.

2. Split the computation into multiple statements:
   Breaking the code into smaller expressions helps Scala infer the correct types incrementally, avoiding nested effects.
    val x: Int < S1 = computeFirst()
    val y: Int < S2 = useResult(x)

""")
opaque type CanLift[A] = Null

object CanLiftMacro:
    inline given derived[A](using inline ng: NotGiven[A <:< (Any < Nothing)]): CanLift[A] = ${ liftImpl[A] }

    private[internal] def liftImpl[A: Type](using Quotes): Expr[CanLift[A]] =
        import quotes.reflect.*
        val tpe = TypeRepr.of[A]
        val sym = tpe.typeSymbol

        // if tpe <:< TypeRepr.of[Any < Nothing] && !(tpe =:= TypeRepr.of[Nothing]) then
        //     report.errorAndAbort(s"cannot lift ${sym.name} to ${sym.name} < S : ${tpe.show}")

        if sym.fullName.startsWith("kyo.") && sym.flags.is(Flags.Module) && !sym.flags.is(Flags.Case) then
            report.errorAndAbort(s"Cannot lift '${sym.fullName}' to a '${sym.name} < S'", Position.ofMacroExpansion)
        '{ CanLift.unsafe.bypass.asInstanceOf[CanLift[A]] }
    end liftImpl

end CanLiftMacro

object CanLift:

    export CanLiftMacro.derived

    inline given CanLift[Nothing] = CanLift.unsafe.bypass

    object unsafe:
        /** Unconditionally provides CanLift evidence for any type.
          *
          * Warning: This bypasses normal type safety checks and should only be used when you can guarantee through other means that no
          * problematic effect nesting will occur.
          */
        inline given bypass[A]: CanLift[A] = null
    end unsafe
end CanLift

object LiftMacro:

    def liftMacro[A: Type, S: Type](v: Expr[A])(using Quotes): Expr[A < S] =
        import quotes.reflect.*

        val sourceTpe = TypeRepr.of[A]
        val tpe       = sourceTpe.dealias
        val sym       = tpe.typeSymbol

        inline def isNothing = tpe =:= TypeRepr.of[Nothing]
        inline def isAnyVal  = tpe <:< TypeRepr.of[AnyVal]
        inline def isTuple   = tpe <:< TypeRepr.of[Tuple]

        inline def isPending = tpe <:< TypeRepr.of[Any < Nothing]

        inline def isOpaque   = sym.flags.is(Flags.Opaque)
        inline def isConcrete = tpe.typeSymbol.isClassDef

        /*
        val tags =
            if isNothing then List("isNothing")
            else
                List(
                    if isKyo then "isKyo" else "",
                    if isAnyVal then "isAnyVal" else "",
                    if isOpaque then "isOpaque" else ""
                ).filter(_.nonEmpty).mkString(" ")

        val pos =
            val p     = v.asTerm.pos
            val file  = p.sourceFile.jpath.toString
            val start = p.startLine + 1
            val end   = p.endLine + 1
            if start == end then s"$file:$start" else s"$file:$start-$end"
        end pos

        //val code = v.show.replaceAll("\n", "\\\\n").replaceAll("\"", "'")

        //println(s"lift,$pos,${sourceTpe.show},${tpe.show},$tags,$code")
         */

        if isNothing || isAnyVal || isTuple then
            '{ $v.asInstanceOf[A < S] }
        else if isPending then
            '{ Nested($v) }
        else if isOpaque || isConcrete then
            '{ $v.asInstanceOf[A < S] }
        else
            '{ defaultLift($v) }
        end if

    end liftMacro

    final def defaultLift[A, S](v: A): A < S =
        v match
            case kyo: Kyo[?, ?] => Nested(kyo)
            case _              => v.asInstanceOf[A < S]

end LiftMacro
