package kyo.test.prop.internal

/** Rose tree carrying a value together with a lazily-computed sequence of strictly-smaller subtrees.
  *
  * This is the core of integrated (Hedgehog-style) shrinking: a generated value and the full lazy tree of its shrink candidates travel
  * together, so `map`/`flatMap`/`filter` propagate shrinking structurally instead of dropping it.
  *
  *   - `value` is the generated value at this node.
  *   - `shrinks()` lazily produces the immediate children; each child is itself a `Tree[A]` whose own `shrinks()` expands further. The
  *     children are deterministic for primitives (they are the value's `Shrink` candidates) and reproducible elsewhere because the seed is
  *     pure and splittable.
  *
  * @tparam A
  *   the type of value carried at each node
  */
final case class Tree[+A](value: A, shrinks: () => LazyList[Tree[A]]):

    /** Map every node's value with `f`, recursively mapping all subtrees. Shrinking is preserved: the shape of the tree is unchanged, only
      * the values are transformed.
      */
    def map[B](f: A => B): Tree[B] =
        Tree(f(value), () => shrinks().map(_.map(f)))

    /** Compose this tree with `f`, which produces an inner tree from each value.
      *
      * The result's value is the inner tree's value. Its shrinks are the outer shrinks (each re-`flatMap`ped through `f`, so they keep
      * generating fresh inner trees) followed by the inner tree's own shrinks. This is the standard monadic rose-tree bind: shrinking first
      * tries to simplify the source, then the result of `f`.
      */
    def flatMap[B](f: A => Tree[B]): Tree[B] =
        val inner = f(value)
        Tree(inner.value, () => shrinks().map(_.flatMap(f)) #::: inner.shrinks())

    /** Prune the shrink subtrees, keeping only candidates whose value satisfies `p`.
      *
      * A child whose value satisfies `p` is kept (with its own subtrees recursively pruned). A child whose value fails `p` is dropped, but
      * its satisfying descendants are spliced in, so no valid shrink path is lost.
      */
    def filter(p: A => Boolean): LazyList[Tree[A]] =
        shrinks().flatMap(t => if p(t.value) then LazyList(t.prune(p)) else t.filter(p))

    /** Recursively apply `filter`'s predicate to this node's own subtrees, retaining this node's value. */
    private def prune(p: A => Boolean): Tree[A] =
        Tree(value, () => filter(p))

    /** Applicative product of two trees: combine the two root values with `f`, and interleave the two subtrees' shrinks so that BOTH
      * components minimize independently.
      *
      * The node value is `f(this.value, that.value)`. The children are the shrinks of `this` (each combined with `that` held fixed)
      * followed by the shrinks of `that` (each combined with `this` held fixed). Holding one component fixed while shrinking the other,
      * one component at a time, lets the greedy shrink walk reach the component-wise minimum. This is the standard applicative rose-tree
      * product and is distinct from `flatMap`, whose monadic bind couples the two components (re-flatMapping outer shrinks through `f`)
      * and so cannot minimize them independently.
      */
    def zipWith[B, C](that: Tree[B])(f: (A, B) => C): Tree[C] =
        Tree(
            f(value, that.value),
            () => shrinks().map(_.zipWith(that)(f)) #::: that.shrinks().map(this.zipWith(_)(f))
        )

end Tree

object Tree:

    /** A leaf: a value with no shrink candidates. */
    def leaf[A](value: A): Tree[A] =
        Tree(value, () => LazyList.empty)

    /** Build a tree from a value and a deterministic step function producing its immediate shrink candidates. Each candidate is expanded
      * recursively via the same step, yielding the full lazy shrink tree.
      */
    def unfold[A](a: A)(next: A => Iterable[A]): Tree[A] =
        Tree(a, () => LazyList.from(next(a)).map(unfold(_)(next)))

end Tree
