package kyo

import kyo.debug.Debug
import kyo.internal.Zippable
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx))

    /** Forks this computation using the Async effect and returns its result as a `Fiber[A, Abort[E]]`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    inline def fork(
        using frame: Frame
    ): Fiber[A, Abort[E]] < (Sync & Ctx) =
        Fiber.init(effect)

    /** Forks this computation using the Async effect and returns its result as a `Fiber[A, Abort[E]]`, managed by the Resource effect.
      * Unlike `fork`, which creates an unmanaged fiber, `forkScoped` ensures that the fiber is properly cleaned up when the enclosing scope
      * is closed, preventing resource leaks.
      *
      * @return
      *   A computation that produces the result of this computation with Async and Resource effects
      */
    inline def forkScoped(
        using frame: Frame
    ): Fiber[A, Abort[E]] < (Sync & Ctx & Resource) =
        Kyo.acquireRelease(Fiber.init(effect))(_.interrupt.unit)

    /** Performs this computation and then the next one in parallel, discarding the result of this computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of `next`
      */
    @targetName("zipRightPar")
    def &>[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        i1: Isolate[Ctx, Sync, Any],
        i2: Isolate[Ctx1, Sync, Any],
        f: Frame
    ): A1 < (Abort[E | E1] & Async & Ctx & Ctx1) =
        for
            fiberA  <- Fiber.init(using i1)(effect)
            fiberA1 <- Fiber.init(using i2)(next)
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
        i1: Isolate[Ctx, Sync, Any],
        i2: Isolate[Ctx1, Sync, Any],
        f: Frame
    ): A < (Abort[E | E1] & Async & Ctx & Ctx1) =
        for
            fiberA  <- Fiber.init(using i1)(effect)
            fiberA1 <- Fiber.init(using i2)(next)
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
    def <&>[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        i1: Isolate[Ctx, Sync, Any],
        i2: Isolate[Ctx1, Sync, Any],
        f: Frame,
        zippable: Zippable[A, A1]
    ): zippable.Out < (Abort[E | E1] & Async & Ctx & Ctx1) =
        for
            fiberA  <- Fiber.init(using i1)(effect)
            fiberA1 <- Fiber.init(using i2)(next)
            a       <- fiberA.join
            a1      <- fiberA1.join
        yield zippable.zip(a, a1)

end extension

extension [A, E, S](fiber: Fiber[A, Abort[E]] < S)

    /** Joins the fiber and returns its result as a `A`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def join(using Frame): A < (S & Abort[E] & Async) =
        fiber.map(_.get)

    /** Awaits the completion of the fiber and returns its result as a `Unit`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def awaitCompletion(using Frame): Unit < (S & Async) =
        fiber.map(_.getResult.unit)
end extension
