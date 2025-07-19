package kyo

import kyo.debug.Debug
import kyo.internal.Zippable
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

extension [A, E, S](effect: A < (Abort[E] & Async & S))

    /** Forks this computation, returning a fiber. Guarantees eventual fiber interruption using [[Scope]] effect.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def fork[S2](
        using
        isolate: Isolate[S, Sync, S2],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[A, reduce.SReduced & S2] < (Sync & S & Scope) =
        Fiber.init(effect)

    /** Forks this computation and uses the resulting fiber within a scoped function [[f]]. Guarantees fiber interruption after usage.
      *
      * @param f
      *   A function using the forked fiber to produce a new effect
      * @return
      *   A computation that produces the result of [[f]] when applied to the forked fiber
      */
    def forkUsing[S2](
        using
        isolate: Isolate[S, Sync, S2],
        reduce: Reducible[Abort[E]],
        frame: Frame
    )[B, S3](f: Fiber[A, reduce.SReduced & S2] => B < S3): B < (Sync & S & S3) =
        Fiber.use(effect)(f)

    /** Forks this computation, returning a fiber. Does not guarantee fiber interruption.
      *
      * @return
      *   A computation that produces the result of this computation with Async and Resource effects
      */
    def forkUnscoped[S2](
        using
        isolate: Isolate[S, Sync, S2],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[A, reduce.SReduced & S2] < (Sync & S) =
        Fiber.initUnscoped(effect)

    /** Performs this computation and then the next one in parallel, discarding the result of this computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of `next`
      */
    @targetName("zipRightPar")
    def &>[A1, E1, S2, S3](next: A1 < (Abort[E1] & Async & S2))(
        using
        fr: Frame,
        i1: Isolate[S, Sync, S3],
        i2: Isolate[S2, Sync, S3],
        tag1: Tag[Abort[E]],
        tag2: Tag[Abort[E1]]
    ): A1 < (Abort[E | E1] & Async & S & S2 & S3) =
        for
            left  <- Fiber.initUnscoped(using i1)(effect)
            right <- Fiber.initUnscoped(using i2)(next)
            _     <- left.await
            a1    <- right.join
        yield a1

    /** Performs this computation and then the next one in parallel, discarding the result of the next computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of this computation
      */
    @targetName("zipLeftPar")
    def <&[A1, E1, S2, S3](next: A1 < (Abort[E1] & Async & S2))(
        using
        f: Frame,
        i1: Isolate[S, Sync, S3],
        i2: Isolate[S2, Sync, S3],
        tag1: Tag[Abort[E]],
        tag2: Tag[Abort[E1]]
    ): A < (Abort[E | E1] & Async & S & S2 & S3) =
        for
            left  <- Fiber.initUnscoped(using i1)(effect)
            right <- Fiber.initUnscoped(using i2)(next)
            a     <- left.join
            _     <- right.await
        yield a

    /** Performs this computation and then the next one in parallel, returning both results as a tuple.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces a tuple of both results
      */
    @targetName("zipPar")
    def <&>[A1, E1, S2, S3](next: A1 < (Abort[E1] & Async & S2))(
        using
        fr: Frame,
        i1: Isolate[S, Sync, S3],
        i2: Isolate[S2, Sync, S3],
        zippable: Zippable[A, A1],
        tag1: Tag[Abort[E]],
        tag2: Tag[Abort[E1]]
    ): zippable.Out < (Abort[E | E1] & Async & S & S2 & S3) =
        for
            left  <- Fiber.initUnscoped(using i1)(effect)
            right <- Fiber.initUnscoped(using i2)(next)
            a     <- left.join
            a1    <- right.join
        yield zippable.zip(a, a1)

end extension

extension [A, E, S, S2](fiber: Fiber[A, Abort[E] & S2] < S)

    /** Joins the fiber and returns its result as a `A`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def join(using Frame, Tag[Abort[E]]): A < (S & S2 & Abort[E] & Async) =
        fiber.map(_.get)

    /** Awaits the completion of the fiber and returns its result as a `Unit`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def await(using Frame): Result[E, A] < (S & S2 & Async) =
        fiber.map(_.getResult)
end extension
