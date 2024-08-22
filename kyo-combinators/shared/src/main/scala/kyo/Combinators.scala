package kyo

import kyo.debug.Debug
import kyo.kernel.Boundary
import kyo.kernel.Reducible
import scala.annotation.implicitNotFound
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.NotGiven

extension [A, S](effect: A < S)

    @targetName("zipRight")
    def *>[A1, S1](next: => A1 < S1)(using Frame): A1 < (S & S1) =
        effect.map(_ => next)

    @targetName("zipLeft")
    def <*[A1, S1](next: => A1 < S1)(using Frame): A < (S & S1) =
        effect.map(e => next.as(e))

    @targetName("zip")
    def <*>[A1, S1](next: => A1 < S1)(using Frame): (A, A1) < (S & S1) =
        effect.map(e => next.map(n => (e, n)))

    inline def as[A1, S1](value: => A1 < S1)(using inline frame: Frame): A1 < (S & S1) =
        effect.map(_ => value)

    def debugValue(using Frame): A < S = Debug(effect)

    def debugTrace(using Frame): A < S = Debug.trace(effect)

    def delayed[S1](duration: Duration < S1)(using Frame): A < (S & S1 & Async) =
        Kyo.sleep(duration) *> effect

    def repeat(policy: Retry.Policy)(using Flat[A < S], Frame): A < (S & Async) =
        def loop(i: Int): A < (S & Async) =
            if i <= 0 then effect
            else loop(i - 1).delayed(policy.backoff(i))

        loop(policy.limit)
    end repeat

    def repeat[S1](limit: => Int < S1)(using Flat[A < S], Frame): A < (S & S1) =
        @tailrec
        def loop(i: Int): A < (S & S1) =
            if i <= 0 then effect
            else loop(i - 1)

        limit.map(l => loop(l))
    end repeat

    def repeat[S1](backoff: Int => Duration, limit: => Int < S1)(using
        Flat[A < S],
        Frame
    ): A < (S & S1 & Async) =
        def loop(i: Int): A < (S & Async) =
            if i <= 0 then effect
            else loop(i - 1).delayed(backoff(i))

        limit.map(l => loop(l))
    end repeat

    def repeatWhile[S1](fn: A => Boolean < S1)(using Flat[A < S], Frame): A < (S & S1 & Async) =
        def loop(last: A): A < (S & S1 & Async) =
            fn(last).map { cont =>
                if cont then effect.map(v => loop(v))
                else last
            }

        effect.map(v => loop(v))
    end repeatWhile

    def repeatWhile[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using
        Flat[A < S],
        Frame
    ): A < (S & S1 & Async) =
        def loop(last: A, i: Int): A < (S & S1 & Async) =
            fn(last, i).map { case (cont, duration) =>
                if cont then Kyo.sleep(duration) *> effect.map(v => loop(v, i + 1))
                else last
            }

        effect.map(v => loop(v, 0))
    end repeatWhile

    def repeatUntil[S1](fn: A => Boolean < S1)(using Flat[A < S], Frame): A < (S & S1 & Async) =
        def loop(last: A): A < (S & S1 & Async) =
            fn(last).map { cont =>
                if cont then last
                else effect.map(v => loop(v))
            }

        effect.map(v => loop(v))
    end repeatUntil

    def repeatUntil[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using
        Flat[A < S],
        Frame
    ): A < (S & S1 & Async) =
        def loop(last: A, i: Int): A < (S & S1 & Async) =
            fn(last, i).map { case (cont, duration) =>
                if cont then last
                else Kyo.sleep(duration) *> effect.map(v => loop(v, i + 1))
            }

        effect.map(v => loop(v, 0))
    end repeatUntil

    def retry(policy: Retry.Policy)(using Flat[A], Frame): A < (S & Async & Abort[Throwable]) =
        Retry[Throwable](policy)(effect)

    def retry[S1](n: => Int < S1)(using Flat[A], Frame): A < (S & S1 & Async & Abort[Throwable]) =
        n.map(nPure => Retry[Throwable](Retry.Policy(_ => Duration.Zero, nPure))(effect))

    def retry[S1](backoff: Int => Duration, n: => Int < S1)(using
        Flat[A],
        Frame
    ): A < (S & S1 & Async & Abort[Throwable]) =
        n.map(nPure => Retry[Throwable](Retry.Policy(backoff, nPure))(effect))

    def forever(using Frame): Nothing < S =
        (effect *> effect.forever) *> (throw new IllegalStateException("infinite loop ended"))

    def when[S1](condition: => Boolean < S1)(using Frame): A < (S & S1 & Abort[Maybe.Empty]) =
        condition.map(c => if c then effect else Abort.fail(Maybe.Empty))

    def explicitThrowable(using Flat[A], Frame): A < (S & Abort[Throwable]) =
        Abort.catching[Throwable](effect)

    def tap[S1](f: A => Any < S1)(using Frame): A < (S & S1) =
        effect.map(a => f(a).as(a))

    def unless[S1](condition: Boolean < S1)(using Frame): A < (S & S1 & Abort[Maybe.Empty]) =
        condition.map(c => if c then Abort.fail(Maybe.Empty) else effect)

end extension

extension [A, S, E](effect: A < (Abort[E] & S))
    def handleAbort(
        using
        ct: ClassTag[E],
        tag: Tag[E],
        flat: Flat[A]
    )(using Frame): Result[E, A] < S =
        Abort.run[E](effect)

    def handleSomeAbort[E1 <: E]: HandleSomeAbort[A, S, E, E1] = HandleSomeAbort(effect)

    def abortToChoice(
        using
        ct: ClassTag[E],
        tag: Tag[E],
        flat: Flat[A]
    )(using Frame): A < (S & Choice) =
        effect.handleAbort.map(e => Choice.get(e.fold(_ => Nil)(List(_))))

    def someAbortToChoice[E1 <: E](using Frame): SomeAbortToChoiceOps[A, S, E, E1] = SomeAbortToChoiceOps(effect)

    def abortToEmpty(
        using
        ct: ClassTag[E],
        tag: Tag[E],
        flat: Flat[A]
    )(using Frame): A < (S & Abort[Maybe.Empty]) =
        effect.handleAbort.map {
            case Result.Fail(_)    => Abort.fail(Maybe.Empty)
            case Result.Panic(e)   => throw e
            case Result.Success(a) => a
        }

    def someAbortToEmpty[E1 <: E]: SomeAbortToEmptyOps[A, S, E, E1] = SomeAbortToEmptyOps(effect)

    def catchAbort[A1 >: A, S1](fn: E => A1 < S1)(
        using
        ct: ClassTag[E],
        tag: Tag[E],
        fl: Flat[A]
    )(using Frame): A1 < (S & S1) =
        effect.handleAbort.map {
            case Result.Fail(e)    => fn(e)
            case Result.Panic(e)   => throw e
            case Result.Success(v) => v
        }

    def catchAbortPartial[A1 >: A, S1](fn: PartialFunction[E, A1 < S1])(
        using
        ct: ClassTag[E],
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

    def swapAbort(
        using
        cta: ClassTag[A],
        cte: ClassTag[E],
        te: Tag[E],
        fl: Flat[A],
        frame: Frame
    ): E < (S & Abort[A]) =
        val handled: Result[E, A] < S = effect.handleAbort
        handled.map((v: Result[E, A]) => Abort.get(v.swap))
    end swapAbort

    def swapSomeAbort[E1 <: E](using Frame): SwapSomeAbortOps[A, S, E, E1] = SwapSomeAbortOps(effect)

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
    def handleEmptyAbort(using f: Flat[A], frame: Frame): Maybe[A] < S =
        Abort.run[Maybe.Empty](effect).map {
            case Result.Fail(_)    => Maybe.Empty
            case Result.Panic(e)   => throw e
            case Result.Success(a) => Maybe.Defined(a)
        }

    def emptyAbortToChoice(using f: Flat[A], frame: Frame): A < (S & Choice) =
        effect.someAbortToChoice[Maybe.Empty]()

    def emptyAbortToFailure[S1](failure: => E < S1)(using f: Flat[A], frame: Frame): A < (S & S1 & Abort[E]) =
        for
            f   <- failure
            res <- effect.handleSomeAbort[Maybe.Empty]()
        yield res match
            case Result.Fail(_)    => Abort.get(Result.Fail(f))
            case Result.Panic(e)   => Abort.get(Result.Panic(e))
            case Result.Success(a) => Abort.get(Result.success(a))
end extension

class SomeAbortToChoiceOps[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        ct: ClassTag[E1],
        tag: Tag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): A < (S & reduce.SReduced & Choice) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map(e => Choice.get(e.fold(_ => Nil)(List(_))))

end SomeAbortToChoiceOps

class SomeAbortToEmptyOps[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        ct: ClassTag[E1],
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
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        ct: ClassTag[E1],
        tag: Tag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): Result[E1, A] < (S & reduce.SReduced) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)])

end HandleSomeAbort

class CatchSomeAbort[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        reduce: Reducible[Abort[ER]],
        ct: ClassTag[E1],
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
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        ct: ClassTag[E1],
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
    def apply[ER]()(
        using
        ev: E => E1 | ER,
        reduce: Reducible[Abort[ER]],
        ct: ClassTag[E1],
        tag: Tag[E1],
        f: Flat[A],
        frame: Frame
    ): E1 < (S & reduce.SReduced & Abort[A]) =
        val handled = Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)])
        handled.map((v: Result[E1, A]) => Abort.get(v.swap))
    end apply
end SwapSomeAbortOps

extension [A, S, E](effect: A < (S & Env[E]))
    def provide[S1, E1 >: E, ER](dependency: E1 < S1)(
        using
        ev: E => E1 & ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1],
        frame: Frame
    ): A < (S & S1 & reduce.SReduced) =
        dependency.map(d => Env.run[E1, A, S, ER](d)(effect.asInstanceOf[A < (S & Env[E1 | ER])]))

    def provideLayer[S1, S2, E1 >: E, ER](layer: Layer[E1, S1] < S2)(
        using
        ev: E => E1 & ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1],
        frame: Frame
    ): A < (S & S1 & S2 & Memo & reduce.SReduced) =
        for
            l  <- layer
            tm <- l.run
            e1 = tm.get[E1]
        yield effect.provide(e1)

end extension

extension [A, S](effect: A < (S & Choice))
    def filterChoice[S1](fn: A => Boolean < S1)(using Frame): A < (S & S1 & Choice) =
        effect.map(a => fn(a).map(b => Choice.dropIf(!b)).as(a))

    def handleChoice(using Flat[A], Frame): Seq[A] < S = Choice.run(effect)

    def choiceToAbort[E, S1](error: => E < S1)(
        using
        Flat[A],
        Frame
    ): A < (S & S1 & Abort[E]) =
        Choice.run(effect).map {
            case s if s.isEmpty => Kyo.fail[E, S1](error)
            case s              => s.head
        }

    def choiceToThrowable(using Flat[A], Frame): A < (S & Abort[Throwable]) =
        choiceToAbort[Throwable, Any](new NoSuchElementException("head of empty list"))

    def choiceToEmpty(using Flat[A], Frame): A < (S & Abort[Maybe.Empty]) =
        choiceToAbort(Maybe.Empty)
end extension

extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx))

    def fork(
        using
        flat: Flat[A],
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]],
        frame: Frame
    ): Fiber[E, A] < (IO & Ctx) =
        Async.run(effect)(using flat, boundary)

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

    def join(using reduce: Reducible[Abort[E]], frame: Frame): A < (S & reduce.SReduced & Async) =
        fiber.map(_.get)

    def awaitCompletion(using Flat[A], Frame): Unit < (S & Async) =
        fiber.map(_.getResult.unit)
end extension

extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx))
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
