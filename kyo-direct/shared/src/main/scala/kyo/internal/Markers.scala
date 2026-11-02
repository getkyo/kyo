package kyo.internal

import kyo.*
import scala.quoted.*

/** The markers a lowering recognizes in a typed body.
  *
  * A sequencing marker wraps a computation `A < S` and exposes its result `A` to the surrounding code; the lowering binds the
  * computation and continues with the result. A pass-through marker wraps a computation and keeps it as the pending value; the
  * lowering erases it. kyo-direct's `.now` and `.later` are the two for `direct` blocks; the `.kyo` language registers the markers
  * its plugin plants. The core never depends on which syntax produced a marker.
  */
private[kyo] trait Markers:

    /** The computation a sequencing marker wraps, its exposed result type, and the effect row it records. */
    def sequencing(using q: Quotes)(tree: q.reflect.Term): Maybe[(q.reflect.Term, q.reflect.TypeRepr, q.reflect.TypeRepr)]

    /** The computation a pass-through marker wraps. */
    def passThrough(using q: Quotes)(tree: q.reflect.Term): Maybe[q.reflect.Term]

    /** True for the call of a boundary: a nested boundary's expansion carries it as the `Inlined` node's call. */
    def isBoundary(using q: Quotes)(call: q.reflect.Tree): Boolean
end Markers

/** kyo-direct's markers: `.now` sequences, `.later` passes through. */
private[kyo] object DirectMarkers extends Markers:

    /** The module class holding the top-level `now` and `later` of Direct.scala, which is what their symbols are owned by. */
    private def owner(using q: Quotes): q.reflect.Symbol =
        q.reflect.Symbol.requiredModule("kyo.Direct$package").moduleClass

    def sequencing(using q: Quotes)(tree: q.reflect.Term): Maybe[(q.reflect.Term, q.reflect.TypeRepr, q.reflect.TypeRepr)] =
        import q.reflect.*
        tree match
            case Apply(TypeApply(fun, List(result, row)), List(computation))
                if fun.symbol.name == "now" && fun.symbol.owner.equals(owner) =>
                Present((computation, result.tpe, row.tpe))
            case _ => Absent
        end match
    end sequencing

    def passThrough(using q: Quotes)(tree: q.reflect.Term): Maybe[q.reflect.Term] =
        import q.reflect.*
        tree match
            case Apply(TypeApply(fun, List(_, _)), List(computation)) if fun.symbol.name == "later" && fun.symbol.owner.equals(owner) =>
                Present(computation)
            case _ => Absent
        end match
    end passThrough

    def isBoundary(using q: Quotes)(call: q.reflect.Tree): Boolean =
        call.symbol.name == "direct" && call.symbol.owner.equals(owner)
end DirectMarkers
