package kyo

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
    def *>[A1, S1](next: => A1 < S1): A1 < (S & S1) =
        effect.map(_ => next)

    @targetName("zipLeft")
    def <*[A1, S1](next: => A1 < S1): A < (S & S1) =
        effect.map(e => next.as(e))

    @targetName("zip")
    def <*>[A1, S1](next: => A1 < S1): (A, A1) < (S & S1) =
        effect.map(e => next.map(n => (e, n)))

    inline def as[A1, S1](value: => A1 < S1): A1 < (S & S1) =
        effect.map(_ => value)

    def debug: A < (S & IO) =
        effect.tap(value => Console.println(value.toString))

    def debug(prefix: => String): A < (S & IO) =
        effect.tap(value => Console.println(s"$prefix: $value"))

    def delayed[S1](duration: Duration < S1): A < (S & S1 & Async) =
        Kyo.sleep(duration) *> effect

    def repeat(policy: Retry.Policy)(using Flat[A < S]): A < (S & Async) =
        def loop(i: Int): A < (S & Async) =
            if i <= 0 then effect
            else loop(i - 1).delayed(policy.backoff(i))

        loop(policy.limit)
    end repeat

    def repeat[S1](limit: => Int < S1)(using Flat[A < S]): A < (S & S1) =
        @tailrec
        def loop(i: Int): A < (S & S1) =
            if i <= 0 then effect
            else loop(i - 1)

        limit.map(l => loop(l))
    end repeat

    def repeat[S1](backoff: Int => Duration, limit: => Int < S1)(using
        Flat[A < S]
    ): A < (S & S1 & Async) =
        def loop(i: Int): A < (S & Async) =
            if i <= 0 then effect
            else loop(i - 1).delayed(backoff(i))

        limit.map(l => loop(l))
    end repeat

    def repeatWhile[S1](fn: A => Boolean < S1)(using Flat[A < S]): A < (S & S1 & Async) =
        def loop(last: A): A < (S & S1 & Async) =
            fn(last).map { cont =>
                if cont then effect.map(v => loop(v))
                else last
            }

        effect.map(v => loop(v))
    end repeatWhile

    def repeatWhile[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using
        Flat[A < S]
    ): A < (S & S1 & Async) =
        def loop(last: A, i: Int): A < (S & S1 & Async) =
            fn(last, i).map { case (cont, duration) =>
                if cont then Kyo.sleep(duration) *> effect.map(v => loop(v, i + 1))
                else last
            }

        effect.map(v => loop(v, 0))
    end repeatWhile

    def repeatUntil[S1](fn: A => Boolean < S1)(using Flat[A < S]): A < (S & S1 & Async) =
        def loop(last: A): A < (S & S1 & Async) =
            fn(last).map { cont =>
                if cont then last
                else effect.map(v => loop(v))
            }

        effect.map(v => loop(v))
    end repeatUntil

    def repeatUntil[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using
        Flat[A < S]
    ): A < (S & S1 & Async) =
        def loop(last: A, i: Int): A < (S & S1 & Async) =
            fn(last, i).map { case (cont, duration) =>
                if cont then last
                else Kyo.sleep(duration) *> effect.map(v => loop(v, i + 1))
            }

        effect.map(v => loop(v, 0))
    end repeatUntil

    def retry(policy: Retry.Policy)(using Flat[A]): A < (S & Async & Abort[Throwable]) =
        Retry[Throwable](policy)(effect)

    def retry[S1](n: => Int < S1)(using Flat[A]): A < (S & S1 & Async & Abort[Throwable]) =
        n.map(nPure => Retry[Throwable](Retry.Policy(_ => Duration.Zero, nPure))(effect))

    def retry[S1](backoff: Int => Duration, n: => Int < S1)(using
        Flat[A]
    ): A < (S & S1 & Async & Abort[Throwable]) =
        n.map(nPure => Retry[Throwable](Retry.Policy(backoff, nPure))(effect))

    def forever: Nothing < S =
        (effect *> effect.forever) *> (throw new IllegalStateException("infinite loop ended"))

    def when[S1](condition: => Boolean < S1): A < (S & S1 & Abort[Maybe.Empty]) =
        condition.map(c => if c then effect else Abort.fail(Maybe.Empty))

    def explicitThrowable(using Flat[A]): A < (S & Abort[Throwable]) =
        Abort.catching[Throwable](effect)

    def tap[S1](f: A => Any < S1): A < (S & S1) =
        effect.map(a => f(a).as(a))

    def unless[S1](condition: Boolean < S1): A < (S & S1 & Abort[Maybe.Empty]) =
        condition.map(c => if c then Abort.fail(Maybe.Empty) else effect)

end extension

extension [A, S, E](effect: A < (Abort[E] & S))
    def handleAbort(
        using
        ct: ClassTag[E],
        tag: Tag[E],
        flat: Flat[A]
    ): Result[E, A] < S =
        Abort.run[E](effect)

    def abortsToChoices(
        using
        ct: ClassTag[E],
        tag: Tag[E],
        flat: Flat[A]
    ): A < (S & Choice) =
        Abort.run[E](effect).map(e => Choice.get(e.fold(_ => Nil)(List(_))))

    def handleSomeAbort[E1 <: E](
        using
        ct: ClassTag[E1],
        tag: Tag[E1],
        reduce: Reducible[Abort[E | E1]],
        flat: Flat[A],
        frame: Frame
    ): Result[E1, A] < (S & reduce.SReduced) =
        Abort.run[E1](effect)

    def catchAbort[A1 >: A, S1](fn: E => A1 < S1)(
        using
        ClassTag[E],
        Tag[E],
        Flat[A]
    ): A1 < (S & S1) =
        Abort.run[E](effect).map {
            case Result.Fail(e)    => fn(e)
            case Result.Panic(e)   => throw e
            case Result.Success(v) => v
        }

    def catchAbortPartial[A1 >: A, S1](fn: PartialFunction[E, A1 < S1])(
        using
        ClassTag[E],
        Tag[E],
        Flat[A]
    ): A1 < (S & S1 & Abort[E]) =
        Abort.run[E](effect).map {
            case Result.Fail(e) =>
                if fn.isDefinedAt(e) then fn(e)
                else Abort.fail(e)
            case Result.Panic(e)   => throw e
            case Result.Success(v) => v
        }

    // def catchSomeAbort[E1](using
    //     ct: ClassTag[E1],
    //     reduce: Abort.HasAbort[E1, E],
    //     f: Flat[A]
    // ): [A1 >: A, S1] => (E1 => A1 < S1) => A1 < (S & S1 & ha.Remainder) =
    //     [A1 >: A, S1] =>
    //         (fn: E1 => A1 < S1) =>
    //             Abort.run[E1](effect).map {
    //                 case Left(e1) => fn(e1)
    //                 case Right(a) => a
    //         }

    // def catchSomeAbortPartial[E1](using
    //     ct: ClassTag[E1],
    //     ha: Abort.HasAbort[E1, E],
    //     f: Flat[A]
    // ): [A1 >: A, S1] => PartialFunction[E1, A1 < S1] => A1 < (S & S1 & Abort[E]) =
    //     [A1 >: A, S1] =>
    //         (fn: PartialFunction[E1, A1 < S1]) =>
    //             Abort.run[E1](effect).map {
    //                 case Left(e1) if fn.isDefinedAt(e1) => fn(e1)
    //                 case Left(e1)                       => Abort.fail[E1](e1)
    //                 case Right(a)                       => a
    //                 // Need asInstanceOf because compiler doesn't know ha.Remainder & Abort[E1]
    //                 // is the same as Abort[E]
    //             }.asInstanceOf[A1 < (S & S1 & Abort[E])]

    def swapAbort(
        using
        cte: ClassTag[E],
        cta: ClassTag[A],
        tagE: Tag[E],
        fl: Flat[A]
    ): E < (S & Abort[A]) =
        val handled: Result[E, A] < S = Abort.run[E](effect)
        handled.map((v: Result[E, A]) => Abort.get(v.swap))
    end swapAbort

    // def swapSomeAbort[E1: ClassTag](
    //     using
    //     ha: Abort.HasAbort[E1, E],
    //     cte: ClassTag[E],
    //     cta: ClassTag[A],
    //     f: Flat[A]
    // ): E1 < (S & ha.Remainder & Abort[A]) =
    //     val handled = Abort.run[E1](effect)
    //     handled.map((v: Either[E1, A]) => Abort.get(v.swap))
    // end swapSomeAbort

    // def implicitThrowable(
    //     using
    //     f: Flat[A],
    //     ha: Abort.HasAbort[Throwable, E]
    // ): A < (S & ha.Remainder) =
    //     Abort.run[Throwable](effect).map {
    //         case Right(a) => a
    //         case Left(e)  => throw e
    //     }
end extension

extension [A, S, E](effect: A < (S & Env[E]))
    def provide[S1, E1 >: E, ER](dependency: E1 < S1)(
        using
        ev: E => E1 | ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1]
    ): A < (S & S1 & reduce.SReduced) =
        dependency.map(d => Env.run[E1, A, S, ER](d)(effect.asInstanceOf[A < (S & Env[E1 | ER])]))

end extension

extension [A, S](effect: A < (S & Choice))
    def filterChoice[S1](fn: A => Boolean < S1): A < (S & S1 & Choice) =
        effect.map(a => fn(a).map(b => Choice.dropIf(!b)).as(a))

    def handleChoice(using Flat[A]): Seq[A] < S = Choice.run(effect)

    def choicesToAbort[E, S1](error: => E < S1)(
        using Flat[A]
    ): A < (S & S1 & Abort[E]) =
        Choice.run(effect).map {
            case s if s.isEmpty => Kyo.fail[E, S1](error)
            case s              => s.head
        }

    def choicesToThrowable(using Flat[A]): A < (S & Abort[Throwable]) =
        choicesToAbort[Throwable, Any](new NoSuchElementException("head of empty list"))

    def choicesToUnit(using Flat[A]): A < (S & Abort[Unit]) =
        choicesToAbort(())
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

    def awaitCompletion(using Flat[A]): Unit < (S & Async) =
        fiber.map(_.getResult.unit)
end extension

extension [A](effect: A < Async)
    @targetName("zipRightPar")
    def &>[A1](next: A1 < Async)(
        using
        Flat[A],
        Flat[A1]
    ): A1 < Async =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
            _       <- fiberA.awaitCompletion
            a1      <- fiberA1.join
        yield a1

    @targetName("zipLeftPar")
    def <&[A1](next: A1 < Async)(
        using
        Flat[A],
        Flat[A1]
    ): A < Async =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
            a       <- fiberA.join
            _       <- fiberA1.awaitCompletion
        yield a

    @targetName("zipPar")
    def <&>[A1](next: A1 < Async)(
        using
        Flat[A],
        Flat[A1]
    ): (A, A1) < Async =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
            a       <- fiberA.join
            a1      <- fiberA1.join
        yield (a, a1)
end extension
