package kyo

import kyo.debug.Debug
import kyo.kernel.Boundary
import kyo.kernel.Reducible
import scala.annotation.tailrec
import scala.annotation.targetName

extension [A, S](effect: A < S)

    /** Performs this computation and then the next one, discarding the result of this computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of `next`
      */
    @targetName("zipRight")
    def *>[A1, S1](next: => A1 < S1)(using Frame): A1 < (S & S1) =
        effect.map(_ => next)

    /** Performs this computation and then the next one, discarding the result of the next computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of this computation
      */
    @targetName("zipLeft")
    def <*[A1, S1](next: => A1 < S1)(using Frame): A < (S & S1) =
        effect.map(e => next.as(e))

    /** Performs this computation and then the next one, returning both results as a tuple.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces a tuple of both results
      */
    @targetName("zip")
    def <*>[A1, S1](next: => A1 < S1)(using Frame): (A, A1) < (S & S1) =
        effect.map(e => next.map(n => (e, n)))

    /** Performs this computation and prints its result to the console.
      *
      * @return
      *   A computation that produces the result of this computation
      */
    def debugValue(using Frame): A < S = Debug(effect)

    /** Performs this computation and prints its result to the console with a detailed execution trace.
      *
      * @return
      *   A computation that produces the result of this computation
      */
    def debugTrace(using Frame): A < S = Debug.trace(effect)

    /** Performs this computation after a delay.
      *
      * @param duration
      *   The duration to delay for
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def delayed(duration: Duration)(using Frame): A < (S & Async) =
        Async.delay(duration)(effect)

    /** Performs this computation repeatedly with a backoff policy.
      *
      * @param policy
      *   The backoff policy to use
      * @return
      *   A computation that produces the result of the last execution
      */
    def repeat(policy: Retry.Policy)(using Flat[A], Frame): A < (S & Async) =
        Loop.indexed { i =>
            if i >= policy.limit then effect.map(Loop.done)
            else effect.delayed(policy.backoff(i)).as(Loop.continue)
        }

    /** Performs this computation repeatedly with a limit.
      *
      * @param limit
      *   The maximum number of times to repeat the computation
      * @return
      *   A computation that produces the result of the last execution
      */
    def repeat(limit: Int)(using Flat[A], Frame): A < S =
        Loop.indexed { i =>
            if i >= limit then effect.map(Loop.done)
            else effect.as(Loop.continue)
        }

    /** Performs this computation repeatedly with a backoff policy and a limit.
      *
      * @param backoff
      *   The backoff policy to use
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def repeat(backoff: Int => Duration, limit: => Int)(using Flat[A], Frame): A < (S & Async) =
        Loop.indexed { i =>
            if i >= limit then effect.map(Loop.done)
            else effect.delayed(backoff(i)).as(Loop.continue)
        }

    /** Performs this computation repeatedly while the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration
      * @return
      *   A computation that produces the result of the last successful execution before the condition becomes false
      */
    def repeatWhile[S1](fn: A => Boolean < S1)(using Flat[A], Frame): A < (S & S1 & Async) =
        def loop(last: A): A < (S & S1 & Async) =
            fn(last).map { cont =>
                if cont then effect.map(v => loop(v))
                else last
            }

        effect.map(v => loop(v))
    end repeatWhile

    /** Performs this computation repeatedly while the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration taking the current value and the number of iterations so far, and returning a tuple
      *   with the condition and the duration to sleep between iterations
      * @return
      *   A computation that produces the result of the last successful execution before the condition becomes false
      */
    def repeatWhile[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using Flat[A], Frame): A < (S & S1 & Async) =
        def loop(last: A, i: Int): A < (S & S1 & Async) =
            fn(last, i).map { case (cont, duration) =>
                if cont then effect.delayed(duration).map(v => loop(v, i + 1))
                else last
            }

        effect.map(v => loop(v, 0))
    end repeatWhile

    /** Performs this computation repeatedly until the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration
      * @return
      *   A computation that produces the result of the first execution where the condition becomes true
      */
    def repeatUntil[S1](fn: A => Boolean < S1)(using Flat[A], Frame): A < (S & S1 & Async) =
        def loop(last: A): A < (S & S1 & Async) =
            fn(last).map { cond =>
                if cond then last
                else effect.map(loop)
            }

        effect.map(v => loop(v))
    end repeatUntil

    /** Performs this computation repeatedly until the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration taking the current value and the number of iterations so far, and returning a tuple
      *   with the condition and the duration to sleep between iterations
      * @return
      *   A computation that produces the result of the first execution where the condition becomes true
      */
    def repeatUntil[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using
        Flat[A],
        Frame
    ): A < (S & S1 & Async) =
        def loop(last: A, i: Int): A < (S & S1 & Async) =
            fn(last, i).map { case (cont, duration) =>
                if cont then last
                else Kyo.sleep(duration) *> effect.map(v => loop(v, i + 1))
            }

        effect.map(v => loop(v, 0))
    end repeatUntil

    /** Performs this computation repeatedly with a retry policy in case of failures.
      *
      * @param policy
      *   The retry policy to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[Throwable] effects
      */
    def retry(policy: Retry.Policy)(using Flat[A], Frame): A < (S & Async & Abort[Throwable]) =
        Retry[Throwable](policy)(effect)

    /** Performs this computation repeatedly with a limit in case of failures.
      *
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[Throwable] effects
      */
    def retry(n: Int)(using Flat[A], Frame): A < (S & Async & Abort[Throwable]) =
        Retry[Throwable](Retry.Policy(_ => Duration.Zero, n))(effect)

    /** Performs this computation repeatedly with a backoff policy and a limit in case of failures.
      *
      * @param backoff
      *   The backoff policy to use
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[Throwable] effects
      */
    def retry(backoff: Int => Duration, n: Int)(using Flat[A], Frame): A < (S & Async & Abort[Throwable]) =
        Retry[Throwable](Retry.Policy(backoff, n))(effect)

    /** Performs this computation indefinitely.
      *
      * @return
      *   A computation that never completes
      * @note
      *   This method is typically used for long-running processes or servers that should run continuously
      */
    def forever(using Frame): Nothing < S =
        (effect *> effect.forever) *> (throw new IllegalStateException("infinite loop ended"))

    /** Performs this computation when the given condition holds.
      *
      * @param condition
      *   The condition to check
      * @return
      *   A computation that produces the result of this computation with Abort[Maybe.Empty] effect
      */
    def when[S1](condition: => Boolean < S1)(using Frame): A < (S & S1 & Abort[Maybe.Empty]) =
        condition.map(c => if c then effect else Abort.fail(Maybe.Empty))

    /** Performs this computation catching any Throwable in an Abort[Throwable] effect.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[Throwable] effect
      */
    def explicitThrowable(using Flat[A], Frame): A < (S & Abort[Throwable]) =
        Abort.catching[Throwable](effect)

    /** Performs this computation and applies an effectful function to its result.
      *
      * @return
      *   A computation that produces the result of this computation
      */
    def tap[S1](f: A => Any < S1)(using Frame): A < (S & S1) =
        effect.map(a => f(a).as(a))

    /** Performs this computation unless the given condition holds, in which case it returns an Abort[Maybe.Empty] effect.
      *
      * @param condition
      *   The condition to check
      * @return
      *   A computation that produces the result of this computation with Abort[Maybe.Empty] effect
      */
    def unless[S1](condition: Boolean < S1)(using Frame): A < (S & S1 & Abort[Maybe.Empty]) =
        condition.map(c => if c then Abort.fail(Maybe.Empty) else effect)

end extension

extension [A, S, E](effect: A < (Abort[E] & S))

    /** Handles the Abort effect and returns its result as a `Result[E, A]`.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect handled
      */
    def handleAbort(
        using
        ct: SafeClassTag[E],
        tag: Tag[E],
        flat: Flat[A]
    )(using Frame): Result[E, A] < S =
        Abort.run[E](effect)

    def handleSomeAbort[E1 <: E]: HandleSomeAbort[A, S, E, E1] = HandleSomeAbort(effect)

    /** Translates the Abort effect to a Choice effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect translated to Choice
      */
    def abortToChoice(
        using
        ct: SafeClassTag[E],
        tag: Tag[E],
        flat: Flat[A]
    )(using Frame): A < (S & Choice) =
        effect.handleAbort.map(e => Choice.get(e.fold(_ => Nil)(List(_))))

    def someAbortToChoice[E1 <: E](using Frame): SomeAbortToChoiceOps[A, S, E, E1] = SomeAbortToChoiceOps(effect)

    /** Translates the Abort[E] effect to an Abort[Maybe.Empty] effect in case of failure.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect translated to Abort[Maybe.Empty]
      */
    def abortToEmpty(
        using
        ct: SafeClassTag[E],
        tag: Tag[E],
        flat: Flat[A]
    )(using Frame): A < (S & Abort[Maybe.Empty]) =
        effect.handleAbort.map {
            case Result.Fail(_)    => Abort.fail(Maybe.Empty)
            case Result.Panic(e)   => throw e
            case Result.Success(a) => a
        }

    def someAbortToEmpty[E1 <: E]: SomeAbortToEmptyOps[A, S, E, E1] = SomeAbortToEmptyOps(effect)

    /** Handles the Abort effect and applies a recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect handled
      */
    def catchAbort[A1 >: A, S1](fn: E => A1 < S1)(
        using
        ct: SafeClassTag[E],
        tag: Tag[E],
        fl: Flat[A]
    )(using Frame): A1 < (S & S1) =
        effect.handleAbort.map {
            case Result.Fail(e)    => fn(e)
            case Result.Panic(e)   => throw e
            case Result.Success(v) => v
        }

    /** Handles the Abort effect and applies a partial recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[E] effect
      */
    def catchAbortPartial[A1 >: A, S1](fn: PartialFunction[E, A1 < S1])(
        using
        ct: SafeClassTag[E],
        tag: Tag[E],
        fl: Flat[A],
        frame: Frame
    ): A1 < (S & S1 & Abort[E]) =
        effect.handleAbort.map {
            case Result.Fail(e) =>
                if fn.isDefinedAt(e) then fn(e)
                else Abort.fail(e)
            case Result.Panic(e)   => throw e
            case Result.Success(v) => v
        }

    def catchSomeAbort[E1 <: E](using Frame): CatchSomeAbort[A, S, E, E1] = CatchSomeAbort(effect)

    def catchSomeAbortPartial[E1 <: E](using Frame): CatchSomeAbortPartialOps[A, S, E, E1] = CatchSomeAbortPartialOps(effect)

    /** Translates the Abort effect by swapping the error and success types.
      *
      * @return
      *   A computation that produces the failure E as result of this computation and the success A as Abort[A] effect
      */
    def swapAbort(
        using
        cta: SafeClassTag[A],
        cte: SafeClassTag[E],
        te: Tag[E],
        fl: Flat[A],
        frame: Frame
    ): E < (S & Abort[A]) =
        val handled: Result[E, A] < S = effect.handleAbort
        handled.map((v: Result[E, A]) => Abort.get(v.swap))
    end swapAbort

    def swapSomeAbort[E1 <: E](using Frame): SwapSomeAbortOps[A, S, E, E1] = SwapSomeAbortOps(effect)

    /** Catches any Throwable in an Abort[Throwable] effect.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[Throwable] effect
      */
    def implicitThrowable[ER](
        using
        ev: E => Throwable | ER,
        f: Flat[A],
        reduce: Reducible[Abort[ER]],
        frame: Frame
    ): A < (S & reduce.SReduced) =
        Abort.run[Throwable](effect.asInstanceOf[A < (Abort[Throwable | ER] & S)])
            .map(_.fold(e => throw e.getFailure)(identity))
end extension

extension [A, S, E](effect: A < (Abort[Maybe.Empty] & S))

    /** Handles the Abort[Maybe.Empty] effect and returns its result as a `Maybe[A]`.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Maybe.Empty] effect handled
      */
    def handleEmptyAbort(using Flat[A], Frame): Maybe[A] < S =
        Abort.run[Maybe.Empty](effect).map {
            case Result.Fail(_)    => Maybe.Empty
            case Result.Panic(e)   => throw e
            case Result.Success(a) => Maybe.Defined(a)
        }

    /** Translates the Abort[Maybe.Empty] effect to a Choice effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Maybe.Empty] effect translated to Choice
      */
    def emptyAbortToChoice(using Flat[A], Frame): A < (S & Choice) =
        effect.someAbortToChoice[Maybe.Empty]()

    /** Handles the Abort[Maybe.Empty] effect translating it to an Abort[E] effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Maybe.Empty] effect translated to Abort[E]
      */
    def emptyAbortToFailure(failure: E)(using Flat[A], Frame): A < (S & Abort[E]) =
        for
            res <- effect.handleSomeAbort[Maybe.Empty]()
        yield res match
            case Result.Fail(_)    => Abort.get(Result.Fail(failure))
            case Result.Panic(e)   => Abort.get(Result.Panic(e))
            case Result.Success(a) => Abort.get(Result.success(a))
end extension

class SomeAbortToChoiceOps[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:

    /** Handles the Abort[E] effect and returns its result as a `Choice`.
      *
      * @return
      *   A computation that produces the result of this computation with Choice effect
      */
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        tag: Tag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): A < (S & reduce.SReduced & Choice) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map(e => Choice.get(e.fold(_ => Nil)(List(_))))

end SomeAbortToChoiceOps

class SomeAbortToEmptyOps[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:

    /** Handles the Abort[E] effect translating it to an Abort[Maybe.Empty] effect.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[Maybe.Empty] effect
      */
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        tag: Tag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): A < (S & reduce.SReduced & Abort[Maybe.Empty]) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map {
            case Result.Fail(_)        => Abort.get(Result.Fail(Maybe.Empty))
            case p @ Result.Panic(e)   => Abort.get(p.asInstanceOf[Result[Nothing, Nothing]])
            case s @ Result.Success(a) => Abort.get(s.asInstanceOf[Result[Nothing, A]])
        }
end SomeAbortToEmptyOps

class HandleSomeAbort[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:

    /** Handles the Abort[E] effect and returns its result as a `Result[E, A]`.
      *
      * @return
      *   A computation that produces the a `Result[E, A]`
      */
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        tag: Tag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): Result[E1, A] < (S & reduce.SReduced) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)])

end HandleSomeAbort

class CatchSomeAbort[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:

    /** Handles the Abort[E] effect and applies a recovery function to the error.
      *
      * @return
      *   A function that takes a recovery function and returns a computation that produces the result of this computation with the Abort[E]
      *   effect handled
      */
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        reduce: Reducible[Abort[ER]],
        ct: SafeClassTag[E1],
        tag: Tag[E1],
        f: Flat[A],
        frame: Frame
    ): [A1 >: A, S1] => (E1 => A1 < S1) => A1 < (S & S1 & reduce.SReduced) =
        [A1 >: A, S1] =>
            (fn: E1 => A1 < S1) =>
                reduce(Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map {
                    case Result.Fail(e1)   => fn(e1)
                    case Result.Success(v) => v
                    case Result.Panic(ex)  => throw ex
                })
end CatchSomeAbort

class CatchSomeAbortPartialOps[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:

    /** Handles the Abort[E] effect and applies a partial recovery function to the error.
      *
      * @return
      *   A function that takes a partial recovery function and returns a computation that produces the result of this computation with the
      *   Abort[E] effect handled
      */
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        tag: Tag[E1],
        f: Flat[A],
        frame: Frame
    ): [A1 >: A, S1] => PartialFunction[E1, A1 < S1] => A1 < (S & S1 & Abort[E]) =
        [A1 >: A, S1] =>
            (fn: PartialFunction[E1, A1 < S1]) =>
                Abort.run[E1](effect).map {
                    case Result.Fail(e1) if fn.isDefinedAt(e1) => fn(e1)
                    case e1: Result.Error[?]                   => Abort.get(e1)
                    case Result.Success(a)                     => a
            }
    end apply
end CatchSomeAbortPartialOps

class SwapSomeAbortOps[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:

    /** Translates the Abort[E] effect to an Abort[A] effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect translated to Abort[A]
      */
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        reduce: Reducible[Abort[ER]],
        ct: SafeClassTag[E1],
        tag: Tag[E1],
        f: Flat[A],
        frame: Frame
    ): E1 < (S & reduce.SReduced & Abort[A]) =
        val handled = Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)])
        handled.map((v: Result[E1, A]) => Abort.get(v.swap))
    end apply
end SwapSomeAbortOps

extension [A, S, E](effect: A < (S & Env[E]))

    /** Handles the Env[E] efffect with the provided value.
      *
      * @param dependency
      *   The value to provide for the environment
      * @return
      *   A computation that produces the result of this computation with the Env[E] effect handled
      */
    def provideValue[E1 >: E, ER](dependency: E1)(
        using
        ev: E => E1 & ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1],
        frame: Frame
    ): A < (S & reduce.SReduced) =
        Env.run[E1, A, S, ER](dependency)(effect.asInstanceOf[A < (S & Env[E1 | ER])])

    /** Handles the Env[E] effect with the provided layer.
      *
      * @param layer
      *   The layers to perform this computation with
      * @return
      *   A computation that produces the result of this computation
      */
    inline def provideLayer[S1, E1 >: E, ER](layer: Layer[E1, S1])(
        using
        ev: E => E1 & ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1],
        frame: Frame
    ): A < (S & S1 & Memo & reduce.SReduced) =
        for
            tm <- layer.run
            e1 = tm.get[E1]
        yield effect.provideValue(e1)

    /** Handles the Env[E] effect with the provided layers via Env.runLayer.
      *
      * @param layers
      *   The layers to handle this computation with
      * @return
      *   A computation that produces the result of this computation
      */
    transparent inline def provide(inline layers: Layer[?, ?]*): A < Nothing =
        Env.runLayer(layers*)(effect)

end extension

extension [A, S](effect: A < (S & Choice))

    /** Filters the result of this computation and performs a `Choice` effect if the condition does not hold.
      *
      * @return
      *   A computation that produces the result of this computation with Choice effect
      */
    def filterChoice[S1](fn: A => Boolean < S1)(using Frame): A < (S & S1 & Choice) =
        effect.map(a => fn(a).map(b => Choice.dropIf(!b)).as(a))

    /** Handles the Choice effect and returns its result as a `Seq[A]`.
      *
      * @return
      *   A computation that produces a sequence of results from this computation with the Choice effect handled
      */
    def handleChoice(using Flat[A], Frame): Seq[A] < S = Choice.run(effect)

    /** Translates the Choice effect to an Abort[E] effect in case the result is empty.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[E] effect
      */
    def choiceToAbort[E](error: E)(using Flat[A], Frame): A < (S & Abort[E]) =
        Choice.run(effect).map {
            case s if s.isEmpty => Kyo.fail(error)
            case s              => s.head
        }

    /** Translates the Choice effect to an Abort[Throwable] effect.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[Throwable] effect
      */
    def choiceToThrowable(using Flat[A], Frame): A < (S & Abort[Throwable]) =
        choiceToAbort(new NoSuchElementException("head of empty list"))

    /** Translates the Choice effect to an Abort[Maybe.Empty] effect.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[Maybe.Empty] effect
      */
    def choiceToEmpty(using Flat[A], Frame): A < (S & Abort[Maybe.Empty]) =
        choiceToAbort(Maybe.Empty)
end extension

extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx))

    /** Forks this computation using the Async effect and returns its result as a `Fiber[E, A]`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def fork(
        using
        flat: Flat[A],
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[E, A] < (IO & Ctx) =
        Async.run(effect)(using flat, boundary)

    /** Forks this computation using the Async effect and returns its result as a `Fiber[E, A]`, managed by the Resource effect. Unlike
      * `fork`, which creates an unmanaged fiber, `forkScoped` ensures that the fiber is properly cleaned up when the enclosing scope is
      * closed, preventing resource leaks.
      *
      * @return
      *   A computation that produces the result of this computation with Async and Resource effects
      */
    def forkScoped(
        using
        flat: Flat[A],
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[E, A] < (IO & Ctx & Resource) =
        Kyo.acquireRelease(Async.run(effect))(_.interrupt.unit)

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
    def awaitCompletion(using Flat[A], Frame): Unit < (S & Async) =
        fiber.map(_.getResult.unit)
end extension

extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx))

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
        f: Flat[A],
        f1: Flat[A1],
        b: Boundary[Ctx, IO],
        b1: Boundary[Ctx1, IO],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): A1 < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
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
        f: Flat[A],
        f1: Flat[A1],
        b: Boundary[Ctx, IO],
        b1: Boundary[Ctx1, IO],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): A < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
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
        f: Flat[A],
        f1: Flat[A1],
        b: Boundary[Ctx, IO],
        b1: Boundary[Ctx1, IO],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): (A, A1) < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
            a       <- fiberA.join
            a1      <- fiberA1.join
        yield (a, a1)
end extension
