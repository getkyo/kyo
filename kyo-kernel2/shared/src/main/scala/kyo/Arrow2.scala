package kyo

import kyo.Frame

/** A sketch of the arrow hierarchy with the two axes separated.
  *
  * `Arrow` today collapses two independent properties onto one type. `Transform` means both "I am a single
  * link" (the structural fact that `head` is `this`) and "I am a plain transformation" (no region semantics).
  * Map bodies want both. `Handler`, `Catching`, `Binding` and `Finalizer` want only the first: they are single
  * links, but their presence *as a stack entry* is what makes them work, since `find` scans entries for a
  * handler, `lookup` and `resolve` for a binding, and the drain for a finalizer. Because those four obtain the
  * first property by mixing in `Transform`, they silently obtain the second, and a folded arrow can bury one
  * where nothing can find it again.
  *
  * Splitting the axes makes the property a type rather than a runtime check:
  *
  *   - arity: `Link` is one link, `AndThen` and `Chain` are compositions
  *   - kind: `Step` is a plain transformation, `Region` is a marker that must stay an entry
  *
  * `AndThen` then holds a `Step` and another `AndThen` or `Id`, so a normalized run of transformations is a
  * type, its whole spine is normalized by construction, and a region inside one is a compile error rather than
  * something a flag has to rule out.
  *
  * Bodies that would build a deferral are left as `???`: they need `Effect.defer`, which speaks the existing
  * `Arrow`, and this file is a shape to review rather than a working replacement.
  */
sealed trait Arrow2[-A, +B, -S]:

    def frame: Frame

    def apply(v: A): B

    def apply[C, S2](v: A, next: Arrow2[B, C, S2]): C

    type X
    def head: Arrow2[A, X, S]
    def tail: Arrow2[X, B, S]

end Arrow2

object Arrow2:

    def id[A]: Id[A] = Id.asInstanceOf[Id[A]]

    /** Arity: one link. `head` is `this` and there is nothing after it.
      *
      * This is what every single-link arrow needs, whatever its kind, so the two kinds below share it rather
      * than restating it.
      */
    sealed private[kyo] trait Link[-A, B, -S] extends Arrow2[A, B, S]:
        type X = B
        def head = this
        def tail = Arrow2.id[B]

        override def toString: String = s"Arrow(${frame.position.show}, ${frame.snippetShort})"
    end Link

    /** Kind: a plain transformation.
      *
      * Nothing on the stack ever needs to locate one of these, so a run of them is safe to hold folded as a
      * single entry. This is the only thing `AndThen` may contain.
      *
      * A trait rather than a class because six sites fuse a step onto a `Kyo` node, and `Kyo` is a class:
      * `suspendWith` (ArrowEffect.scala:45), the three `handle*With` variants (:161, :195, :230), and the two
      * clause deferrals in the evaluator (Eval.scala:200, :243).
      */
    sealed private[kyo] trait Step[-A, B, -S] extends Link[A, B, S]

    /** `Step` as a class, for the sites that mint a standalone arrow.
      *
      * A mixin forwarder is emitted into every class that mixes a trait in, for each concrete trait member not
      * already implemented in a superclass, and `Function1`'s specialization grid makes that 26 methods per
      * anonymous class. Extending this inherits them instead. Same purpose `TransformBase` serves today.
      */
    abstract private[kyo] class StepBase[-A, B, -S] extends Step[A, B, S]

    /** Kind: a region marker.
      *
      * `Handler`, `Catching`, `Binding` and `Finalizer`. Being an entry is the whole point: fold one into an
      * arrow and the scan that looks for it stops finding it. Deliberately not a `Step`, so it cannot end up
      * inside an `AndThen`.
      *
      * A trait for the same reason `Step` is: `Finalizer` extends `AtomicBoolean`, and `Catching` and
      * `Binding` extend `Kyo`, so neither can take a second superclass.
      */
    sealed private[kyo] trait Region[-A, B, -S] extends Link[A, B, S]

    /** A normalized continuation: `AndThen` or `Id`, and nothing else.
      *
      * Sealed with exactly those two members, so a value of this type is a straight run of steps ending in
      * identity. A union of the two would say the same thing but not typecheck: `Id[B]` is `Arrow2[B, B, Any]`,
      * and only the shape of the tail says that `C` is `B` there, which a union cannot carry.
      */
    sealed private[kyo] trait Cont[-A, +B, -S] extends Arrow2[A, B, S]

    /** Composition: normalized.
      *
      * A straight chain of transformations. The head is a `Step` and the tail is another normalized
      * continuation, so the spine is normalized all the way down and holds no region. That is what lets the
      * stack keep one of these whole as a single entry: it is already in the shape the entries want, so
      * flattening it would rebuild what was just built, and there is nothing inside for a scan to miss.
      */
    final private[kyo] class AndThen[-A, B, +C, -S](
        val t: Step[A, B, S],
        val cont: Cont[B, C, S]
    ) extends Cont[A, C, S]:
        type X = B
        def head  = t
        def tail  = cont
        def frame = Frame.internal

        def apply(v: A): C                                = ???
        def apply[D, S2](v: A, next: Arrow2[C, D, S2]): D = ???

        override def toString: String = s"Arrow.AndThen($t, $cont)"
    end AndThen

    /** Composition: general.
      *
      * Unchanged from `Arrow.Chain`, and it has to stay: a chain is how `map` accumulates, one node per
      * combinator with no walk, so building stays linear in the number of combinators. Normalizing here
      * instead would make building quadratic, which is why the normalized form is a separate type produced
      * once, at the fold, rather than a shape maintained on every `map`.
      */
    private[kyo] class Chain[-A, B, +C, -S](
        val a: Arrow2[A, B, S],
        val b: Arrow2[B, C, S]
    ) extends Arrow2[A, C, S]:
        type X = B
        def head  = a
        def tail  = b
        def frame = Frame.internal

        def apply(v: A): C                                = ???
        def apply[D, S2](v: A, next: Arrow2[C, D, S2]): D = ???
    end Chain

    private[kyo] class Id[A] extends Cont[A, A, Any]:
        type X = A
        def head                      = this
        def tail                      = this
        def frame                     = Frame.internal
        def apply(v: A): A            = v
        override def toString: String = "Arrow(identity)"

        def apply[C, S2](v: A, next: Arrow2[A, C, S2]): C = next(v)
    end Id

    private[kyo] object Id extends Id[Any]

end Arrow2
