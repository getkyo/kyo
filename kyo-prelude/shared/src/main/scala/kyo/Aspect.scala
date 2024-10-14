package kyo

import Aspect.*

/** Represents an aspect in the Kyo effect system.
  *
  * An aspect allows for the modification of behavior across multiple points in a program without changing the code of the affected points
  * directly. It provides a way to "weave" additional behavior into the existing code.
  *
  * @tparam A
  *   The input type of the aspect
  * @tparam B
  *   The output type of the aspect
  * @tparam S
  *   The effect type of the aspect
  */
final class Aspect[A, B, S] private[kyo] (default: Cut[A, B, S])(using Frame) extends Cut[A, B, S]:

    /** Applies this aspect to the given computation.
      *
      * @param v
      *   The input value
      * @param f
      *   The function to apply after the aspect
      * @tparam S2
      *   Additional effects
      * @return
      *   The result of applying the aspect and then the function
      */
    def apply(v: A)(f: A => B < S) =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[A, B, S] @unchecked) =>
                    local.let(map - this) {
                        a(v)(f)
                    }
                case _ =>
                    default(v)(f)
        }

    /** Applies this aspect in a sandboxed environment.
      *
      * This method allows you to apply the aspect without affecting the outer context.
      *
      * @param v
      *   The computation to sandbox
      * @tparam S
      *   Additional effects
      * @return
      *   The result of the sandboxed computation
      */
    def sandbox[S](v: A < S)(using Frame): A < S =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[A, B, S] @unchecked) =>
                    local.let(map - this) {
                        v
                    }
                case _ =>
                    v
        }

    /** Temporarily modifies the behavior of this aspect for the provided computation.
      *
      * @param a
      *   The new cut to apply
      * @param v
      *   The computation to run with the modified aspect
      * @tparam V
      *   The type of the computation result
      * @tparam S2
      *   Additional effects
      * @return
      *   The result of the computation with the modified aspect
      */
    def let[V, S2](a: Cut[A, B, S])(v: V < S2)(using Frame): V < (S & S2) =
        local.use { map =>
            val cut =
                map.get(this) match
                    case Some(b: Cut[A, B, S] @unchecked) =>
                        b.andThen(a)
                    case _ =>
                        a
            local.let(map + (this -> cut))(v)
        }
end Aspect

/** Companion object for Aspect class. */
object Aspect:

    private[kyo] val local = Local.init(Map.empty[Aspect[?, ?, ?], Cut[?, ?, ?]])

    /** Represents a cut in the Kyo aspect-oriented programming model.
      *
      * A cut defines how an aspect modifies or intercepts a computation. It provides a way to inject behavior before, after, or around a
      * given computation.
      *
      * @tparam A
      *   The input type of the cut
      * @tparam B
      *   The output type of the cut
      * @tparam S
      *   The effect type of the cut
      */
    abstract class Cut[A, B, S]:

        /** Applies this cut to the given computation.
          *
          * @param v
          *   The input value
          * @param f
          *   The function to apply after the cut
          * @tparam S2
          *   Additional effects
          * @return
          *   The result of applying the cut and then the function
          */
        def apply(v: A)(f: A => B < S): B < S

        /** Chains this cut with another cut.
          *
          * @param other
          *   The cut to chain after this one
          * @return
          *   A new cut that represents the chained cuts
          */
        def andThen(other: Cut[A, B, S])(using Frame): Cut[A, B, S] =
            new Cut[A, B, S]:
                def apply(v: A)(f: A => B < S) =
                    Cut.this(v)(other(_)(f))
    end Cut

    /** Initializes a new Aspect with the default cut.
      *
      * @tparam A
      *   The input type of the aspect
      * @tparam B
      *   The output type of the aspect
      * @tparam S
      *   The effect type of the aspect
      * @return
      *   A new Aspect instance
      */
    def init[A, B, S](using Frame): Aspect[A, B, S] =
        init(new Cut[A, B, S]:
            def apply(v: A)(f: A => B < S) =
                f(v)
        )

    /** Initializes a new Aspect with a custom default cut.
      *
      * @param default
      *   The default cut to use
      * @tparam A
      *   The input type of the aspect
      * @tparam B
      *   The output type of the aspect
      * @tparam S
      *   The effect type of the aspect
      * @return
      *   A new Aspect instance with the specified default cut
      */
    def init[A, B, S](default: Cut[A, B, S])(using Frame): Aspect[A, B, S] =
        new Aspect[A, B, S](default)

    /** Chains multiple cuts together.
      *
      * @param head
      *   The first cut in the chain
      * @param tail
      *   The remaining cuts to chain
      * @tparam A
      *   The input type of the cuts
      * @tparam B
      *   The output type of the cuts
      * @tparam S
      *   The effect type of the cuts
      * @return
      *   A new cut that represents the chained cuts
      */
    def chain[A, B, S](head: Cut[A, B, S], tail: Seq[Cut[A, B, S]])(using Frame) =
        tail.foldLeft(head)(_.andThen(_))
end Aspect
