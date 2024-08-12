package kyo

import kyo.*
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

extension [A, S, E, ER](effect: A < (Abort[E | ER] & S))
    def handleAborts(
        using
        ct: ClassTag[E],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A]
    ): Result[E, A] < (S & reduce.SReduced) =
        reduce(Abort.run(effect))

    def abortsToChoices(
        using
        ct: ClassTag[E],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A]
    ): A < (S & reduce.SReduced & Choice) =
        reduce(Abort.run[E](effect).map(e => Choice.get(e.fold(_ => Nil)(List(_)))))

    def someAbortsToChoices[E1 <: E: Tag: ClassTag](
        using
        reduce: Reducible[Abort[ER]],
        f: Flat[A]
    ): A < (S & reduce.SReduced & Choice) =
        reduce(Abort.run[E1](effect).map(e => Choice.get(e.fold(_ => Nil)(List(_)))))

    def handleSomeAborts[E1: ClassTag](
        using
        ha: Abort.HasAborts[E1, E],
        f: Flat[A]
    ): Either[E1, A] < (S & ha.Remainder) =
        Abort.run[E1](effect)

    def catchAborts[A1 >: A, S1](fn: E => A1 < S1)(
        using
        ClassTag[E],
        Flat[A]
    ): A1 < (S & S1) =
        Abort.run[E](effect).map {
            case Left(e)  => fn(e)
            case Right(a) => a
        }

    def catchAbortsPartial[A1 >: A, S1](fn: PartialFunction[E, A1 < S1])(
        using
        ClassTag[E],
        Flat[A]
    ): A1 < (S & S1 & Abort[E]) =
        Abort.run[E](effect).map {
            case Left(e) if fn.isDefinedAt(e) => fn(e)
            case Left(e)                      => Abort.fail(e)
            case Right(a)                     => a
        }

    def catchSomeAborts[E1](using
        ct: ClassTag[E1],
        ha: Abort.HasAborts[E1, E],
        f: Flat[A]
    ): [A1 >: A, S1] => (E1 => A1 < S1) => A1 < (S & S1 & ha.Remainder) =
        [A1 >: A, S1] =>
            (fn: E1 => A1 < S1) =>
                Abort.run[E1](effect).map {
                    case Left(e1) => fn(e1)
                    case Right(a) => a
            }

    def catchSomeAbortsPartial[E1](using
        ct: ClassTag[E1],
        ha: Abort.HasAborts[E1, E],
        f: Flat[A]
    ): [A1 >: A, S1] => PartialFunction[E1, A1 < S1] => A1 < (S & S1 & Abort[E]) =
        [A1 >: A, S1] =>
            (fn: PartialFunction[E1, A1 < S1]) =>
                Abort.run[E1](effect).map {
                    case Left(e1) if fn.isDefinedAt(e1) => fn(e1)
                    case Left(e1)                       => Abort.fail[E1](e1)
                    case Right(a)                       => a
                    // Need asInstanceOf because compiler doesn't know ha.Remainder & Abort[E1]
                    // is the same as Abort[E]
                }.asInstanceOf[A1 < (S & S1 & Abort[E])]

    def swapAborts(
        using
        cte: ClassTag[E],
        cta: ClassTag[A],
        fl: Flat[A]
    ): E < (S & Abort[A]) =
        val handled: Either[E, A] < S = Abort.run[E](effect)
        handled.map((v: Either[E, A]) => Abort.get(v.swap))
    end swapAborts

    def swapSomeAborts[E1: ClassTag](
        using
        ha: Abort.HasAborts[E1, E],
        cte: ClassTag[E],
        cta: ClassTag[A],
        f: Flat[A]
    ): E1 < (S & ha.Remainder & Abort[A]) =
        val handled = Abort.run[E1](effect)
        handled.map((v: Either[E1, A]) => Abort.get(v.swap))
    end swapSomeAborts

    def implicitThrowable(
        using
        f: Flat[A],
        ha: Abort.HasAborts[Throwable, E]
    ): A < (S & ha.Remainder) =
        Abort.run[Throwable](effect).map {
            case Right(a) => a
            case Left(e)  => throw e
        }
end extension

extension [A, S, E](effect: A < (S & Env[E]))
    def provide[E1, S1, SR](dependency: E1 < S1)(
        using
        fl: Flat[A],
        he: HasEnvs[E1, E] { type Remainder = SR },
        t: Tag[E1]
    ): A < (S & S1 & SR) =
        dependency.map(d => Env.run[E1, A, S, E, SR](d)(effect))

    def provideAs[E1](
        using
        f: Flat[A],
        t: Tag[E1],
        he: HasEnvs[E1, E]
    ): ProvideAsPartiallyApplied[A, S, E, E1, he.Remainder] =
        ProvideAsPartiallyApplied(effect)
end extension

extension [A, S](effect: A < (S & Choices))
    def filterChoices[S1](fn: A => Boolean < S1): A < (S & S1 & Choices) =
        effect.map(a => Choices.filter(fn(a)).as(a))

    def handleChoices(using Flat[A]): Seq[A] < S = Choices.run(effect)

    def choicesToOptions(using Flat[A]): A < (S & Options) =
        Choices.run(effect).map(seq => Options.get(seq.headOption))

    def choicesToAborts[E, S1](error: => E < S1)(
        using Flat[A]
    ): A < (S & S1 & Abort[E]) =
        Choices.run(effect).map {
            case s if s.isEmpty => Kyo.fail[E, S1](error)
            case s              => s.head
        }

    def choicesToThrowable(using Flat[A]): A < (S & Abort[Throwable]) =
        choicesToAborts[Throwable, Any](new NoSuchElementException("head of empty list"))

    def choicesToUnit(using Flat[A]): A < (S & Abort[Unit]) =
        choicesToAborts(())
end extension

extension [A, S](effect: A < (S & Async))
    def fork(
        using
        @implicitNotFound(
            "Only Async- and IO-based effects can be forked. Found: ${S}"
        ) ev: S => IO,
        f: Flat[A]
    ): Fiber[A] < (S & IO) = Async.run(effect)

    def forkScoped(
        using
        @implicitNotFound(
            "Only Async- and IO-based effects can be forked. Found: ${S}"
        ) ev: S => IO,
        f: Flat[A]
    ): Fiber[A] < (S & IO & Resource) =
        Kyo.acquireRelease(Async.run(effect))(_.interrupt.unit)
end extension

extension [A, S](fiber: Fiber[A] < S)
    def join: A < (S & Async) = Async.get(fiber)
    def awaitCompletion(using Flat[A]): Unit < (S & Async) =
        Kyo.attempt(Async.get(fiber))
            .handleAborts
            .unit
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

final class ProvideAsPartiallyApplied[A, S, E, E1, ER](
    effect: A < (S & Env[E])
)(
    using
    t: Tag[E1],
    he: HasEnvs[E1, E] { type Remainder = ER },
    f: Flat[A]
):
    def apply[S1](dependency: E1 < S1): A < (S & S1 & ER) =
        dependency.map(d => Env.run(d)(effect))
end ProvideAsPartiallyApplied
