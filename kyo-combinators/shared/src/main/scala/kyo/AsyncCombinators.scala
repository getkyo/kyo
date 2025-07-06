package kyo

import kyo.debug.Debug
import kyo.internal.Zippable
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
    inline def fork(using Frame, Isolate.Contextual[Ctx, Sync]): Fiber[E, A] < (Sync & Ctx) =
        Fiber.init(effect)

    /** Forks this computation using the Async effect and returns its result as a `Fiber[E, A]`, managed by the Resource effect. Unlike
      * `fork`, which creates an unmanaged fiber, `forkScoped` ensures that the fiber is properly cleaned up when the enclosing scope is
      * closed, preventing resource leaks.
      *
      * @return
      *   A computation that produces the result of this computation with Async and Resource effects
      */
    inline def forkScoped(using Frame, Isolate.Contextual[Ctx, Sync]): Fiber[E, A] < (Sync & Ctx & Resource) =
        Kyo.acquireRelease(Fiber.init(effect))(_.interrupt)

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
        fr: Frame,
        i1: Isolate.Contextual[Ctx, Sync],
        i2: Isolate.Contextual[Ctx1, Sync]
    ): A1 < (Abort[E | E1] & Async & Ctx & Ctx1) =
        for
            left  <- Fiber.init(using i1)(effect)
            right <- Fiber.init(using i2)(next)
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
    def <&[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        f: Frame,
        i1: Isolate.Contextual[Ctx, Sync],
        i2: Isolate.Contextual[Ctx1, Sync]
    ): A < (Abort[E | E1] & Async & Ctx & Ctx1) =
        for
            left  <- Fiber.init(using i1)(effect)
            right <- Fiber.init(using i2)(next)
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
    def <&>[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        fr: Frame,
        i1: Isolate.Contextual[Ctx, Sync],
        i2: Isolate.Contextual[Ctx1, Sync],
        zippable: Zippable[A, A1]
    ): zippable.Out < (Abort[E | E1] & Async & Ctx & Ctx1) =
        for
            left  <- Fiber.init(using i1)(effect)
            right <- Fiber.init(using i2)(next)
            a     <- left.join
            a1    <- right.join
        yield zippable.zip(a, a1)

end extension

extension [A, E, S](fiber: Fiber[E, A] < S)

    /** Joins the fiber and returns its result as a `A`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def join(using frame: Frame, reduce: Reducible[Abort[E]]): A < (S & reduce.SReduced & Async) =
        fiber.map(_.get)

    /** Awaits the completion of the fiber and returns its result as a `Unit`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def await(using Frame): Result[E, A] < (S & Async) =
        fiber.map(_.getResult)
end extension
