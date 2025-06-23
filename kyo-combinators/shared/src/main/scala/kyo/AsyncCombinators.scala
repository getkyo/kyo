package kyo

import kyo.debug.Debug
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx))

    /** Forks this computation using the Async effect and returns its result as a `Fiber[E, A]`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    inline def fork(
        using frame: Frame
    ): Fiber[E, A] < (Sync & Ctx) =
        Async.run(effect)

    /** Forks this computation using the Async effect and returns its result as a `Fiber[E, A]`, managed by the Resource effect. Unlike
      * `fork`, which creates an unmanaged fiber, `forkScoped` ensures that the fiber is properly cleaned up when the enclosing scope is
      * closed, preventing resource leaks.
      *
      * @return
      *   A computation that produces the result of this computation with Async and Resource effects
      */
    inline def forkScoped(
        using frame: Frame
    ): Fiber[E, A] < (Sync & Ctx & Resource) =
        Kyo.acquireRelease(Async.run(effect))(_.interrupt.unit)

    /** Performs this computation and then the next one in parallel, discarding the result of this computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of `next`
      */
    @targetName("zipRightPar")
    def &>[A1, E1, Ctx1](
        using
        s: Isolate.Contextual[Ctx, Sync],
        s2: Isolate.Contextual[Ctx1, Sync]
    )(next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): A1 < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- Async.run(using s)(effect)
            fiberA1 <- Async.run(using s2)(next)
            _       <- fiberA.awaitCompletion
            a1      <- fiberA1.join
        yield a1

    /** Performs this computation and then the next one in parallel, discarding the result of the next computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of this computation
      */
    @targetName("zipLeftPar")
    def <&[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        s: Isolate.Contextual[Ctx, Sync],
        s2: Isolate.Contextual[Ctx1, Sync]
    )(
        using
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): A < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- Async.run(using s)(effect)
            fiberA1 <- Async.run(using s2)(next)
            a       <- fiberA.join
            _       <- fiberA1.awaitCompletion
        yield a

    /** Performs this computation and then the next one in parallel, returning both results as a tuple.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces a tuple of both results
      */
    @targetName("zipPar")
    def <&>[A1, E1, Ctx1](
        using
        s: Isolate.Contextual[Ctx, Sync],
        s2: Isolate.Contextual[Ctx1, Sync]
    )(next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): (A, A1) < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- Async.run(using s)(effect)
            fiberA1 <- Async.run(using s2)(next)
            a       <- fiberA.join
            a1      <- fiberA1.join
        yield (a, a1)

end extension

extension [A, E, S](fiber: Fiber[E, A] < S)

    /** Joins the fiber and returns its result as a `A`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def join(using reduce: Reducible[Abort[E]], frame: Frame): A < (S & reduce.SReduced & Async) =
        fiber.map(_.get)

    /** Awaits the completion of the fiber and returns its result as a `Unit`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def awaitCompletion(using Frame): Unit < (S & Async) =
        fiber.map(_.getResult.unit)
end extension
