package kyo

import kyo.*
import kyo.Envs.HasEnvs
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

    def as[A1, S1](value: => A1 < S1): A1 < (S & S1) =
        effect.map(_ => value)

    def debug: A < (S & IOs) =
        effect.tap(value => Console.default.println(value.toString))

    def debug(prefix: => String): A < (S & IOs) =
        effect.tap(value => Console.default.println(s"$prefix: $value"))

    def delayed[S1](duration: Duration < S1): A < (S & S1 & Fibers) =
        KYO.sleep(duration) *> effect

    def discard: Unit < S = as(())

    def repeat(policy: Retries.Policy)(using Flat[A < S]): A < (S & Fibers) =
        def loop(i: Int): A < (S & Fibers) =
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
    ): A < (S & S1 & Fibers) =
        def loop(i: Int): A < (S & Fibers) =
            if i <= 0 then effect
            else loop(i - 1).delayed(backoff(i))

        limit.map(l => loop(l))
    end repeat

    def repeatWhile[S1](fn: A => Boolean < S1)(using Flat[A < S]): A < (S & S1 & Fibers) =
        def loop(last: A): A < (S & S1 & Fibers) =
            fn(last).map { cont =>
                if cont then effect.map(v => loop(v))
                else last
            }

        effect.map(v => loop(v))
    end repeatWhile

    def repeatWhile[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using
        Flat[A < S]
    ): A < (S & S1 & Fibers) =
        def loop(last: A, i: Int): A < (S & S1 & Fibers) =
            fn(last, i).map { case (cont, duration) =>
                if cont then KYO.sleep(duration) *> effect.map(v => loop(v, i + 1))
                else last
            }

        effect.map(v => loop(v, 0))
    end repeatWhile

    def repeatUntil[S1](fn: A => Boolean < S1)(using Flat[A < S]): A < (S & S1 & Fibers) =
        def loop(last: A): A < (S & S1 & Fibers) =
            fn(last).map { cont =>
                if cont then last
                else effect.map(v => loop(v))
            }

        effect.map(v => loop(v))
    end repeatUntil

    def repeatUntil[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using
        Flat[A < S]
    ): A < (S & S1 & Fibers) =
        def loop(last: A, i: Int): A < (S & S1 & Fibers) =
            fn(last, i).map { case (cont, duration) =>
                if cont then last
                else KYO.sleep(duration) *> effect.map(v => loop(v, i + 1))
            }

        effect.map(v => loop(v, 0))
    end repeatUntil

    def retry(policy: Retries.Policy)(using Flat[A]): A < (S & Fibers) =
        Retries(policy)(effect)

    def retry[S1](n: => Int < S1)(using Flat[A]): A < (S & S1 & Fibers) =
        n.map(nPure => Retries(Retries.Policy(_ => Duration.Zero, nPure))(effect))

    def retry[S1](backoff: Int => Duration, n: => Int < S1)(using
        Flat[A]
    ): A < (S & S1 & Fibers) =
        n.map(nPure => Retries(Retries.Policy(backoff, nPure))(effect))

    def forever: Nothing < S =
        (effect *> effect.forever) *> (throw new IllegalStateException("infinite loop ended"))

    def when[S1](condition: => Boolean < S1): A < (S & S1 & Options) =
        condition.map(c => if c then effect else Options.empty)

    def explicitThrowable(using Flat[A]): A < (S & Aborts[Throwable]) =
        Aborts.catching[Throwable](effect)

    def tap[S1](f: A => Any < S1): A < (S & S1) =
        effect.map(a => f(a).as(a))

    def unless[S1](condition: Boolean < S1): A < (S & S1 & Options) =
        condition.map(c => if c then Options.empty else effect)

end extension

extension [A, S, E](effect: A < (Aborts[E] & S))
    def handleAborts(
        using
        ClassTag[E],
        Flat[A]
    ): Either[E, A] < S =
        Aborts.run(effect)

    def abortsToOptions(
        using
        ClassTag[E],
        Flat[A]
    ): A < (S & Options) =
        Aborts.run(effect).map(e => Options.get(e.toOption))

    def someAbortsToOptions[E1: ClassTag](
        using
        ha: Aborts.HasAborts[E1, E],
        f: Flat[A]
    ): A < (S & ha.Remainder & Options) =
        Aborts.run[E1](effect).map(e => Options.get(e.toOption))

    def abortsToChoices(
        using
        ClassTag[E],
        Flat[A]
    ): A < (S & Choices) =
        Aborts.run[E](effect).map(e => Choices.get(e.toOption.toList))

    def someAbortsToChoices[E1: Tag: ClassTag](
        using
        ha: Aborts.HasAborts[E1, E],
        f: Flat[A]
    ): A < (S & ha.Remainder & Choices) =
        Aborts.run[E1](effect).map(e => Choices.get(e.toOption.toList))

    def handleSomeAborts[E1: ClassTag](
        using
        ha: Aborts.HasAborts[E1, E],
        f: Flat[A]
    ): Either[E1, A] < (S & ha.Remainder) =
        Aborts.run[E1](effect)

    def catchAborts[A1 >: A, S1](fn: E => A1 < S1)(
        using
        ClassTag[E],
        Flat[A]
    ): A1 < (S & S1) =
        Aborts.run[E](effect).map {
            case Left(e)  => fn(e)
            case Right(a) => a
        }

    def catchAbortsPartial[A1 >: A, S1](fn: PartialFunction[E, A1 < S1])(
        using
        ClassTag[E],
        Flat[A]
    ): A1 < (S & S1 & Aborts[E]) =
        Aborts.run[E](effect).map {
            case Left(e) if fn.isDefinedAt(e) => fn(e)
            case Left(e)                      => Aborts.fail(e)
            case Right(a)                     => a
        }

    def catchSomeAborts[E1](using
        ct: ClassTag[E1],
        ha: Aborts.HasAborts[E1, E],
        f: Flat[A]
    ): [A1 >: A, S1] => (E1 => A1 < S1) => A1 < (S & S1 & ha.Remainder) =
        [A1 >: A, S1] =>
            (fn: E1 => A1 < S1) =>
                Aborts.run[E1](effect).map {
                    case Left(e1) => fn(e1)
                    case Right(a) => a
            }

    def catchSomeAbortsPartial[E1](using
        ct: ClassTag[E1],
        ha: Aborts.HasAborts[E1, E],
        f: Flat[A]
    ): [A1 >: A, S1] => PartialFunction[E1, A1 < S1] => A1 < (S & S1 & Aborts[E]) =
        [A1 >: A, S1] =>
            (fn: PartialFunction[E1, A1 < S1]) =>
                Aborts.run[E1](effect).map {
                    case Left(e1) if fn.isDefinedAt(e1) => fn(e1)
                    case Left(e1)                       => Aborts.fail[E1](e1)
                    case Right(a)                       => a
                    // Need asInstanceOf because compiler doesn't know ha.Remainder & Aborts[E1]
                    // is the same as Aborts[E]
                }.asInstanceOf[A1 < (S & S1 & Aborts[E])]

    def swapAborts(
        using
        cte: ClassTag[E],
        cta: ClassTag[A],
        fl: Flat[A]
    ): E < (S & Aborts[A]) =
        val handled: Either[E, A] < S = Aborts.run[E](effect)
        handled.map((v: Either[E, A]) => Aborts.get(v.swap))
    end swapAborts

    def swapSomeAborts[E1: ClassTag](
        using
        ha: Aborts.HasAborts[E1, E],
        cte: ClassTag[E],
        cta: ClassTag[A],
        f: Flat[A]
    ): E1 < (S & ha.Remainder & Aborts[A]) =
        val handled = Aborts.run[E1](effect)
        handled.map((v: Either[E1, A]) => Aborts.get(v.swap))
    end swapSomeAborts

    def implicitThrowable(
        using
        f: Flat[A],
        ha: Aborts.HasAborts[Throwable, E]
    ): A < (S & ha.Remainder) =
        Aborts.run[Throwable](effect).map {
            case Right(a) => a
            case Left(e)  => throw e
        }
end extension

extension [A, S](effect: A < (S & Options))
    def handleOptions(
        using Flat[A]
    ): Option[A] < S = Options.run(effect)

    def catchOptions[A1 >: A, S1](orElse: => A1 < S1)(
        using Flat[A]
    ): A1 < (S & S1) =
        Options.run(effect).map {
            case None    => orElse
            case Some(a) => a
        }

    def swapOptionsAs[A1, S1](value: => A1 < S1)(
        using Flat[A]
    ): A1 < (S & S1 & Options) =
        Options.run(effect).map {
            case None    => value
            case Some(a) => Options.empty
        }

    def swapOptions(using Flat[A]): Unit < (S & Options) =
        swapOptionsAs(())

    def optionsToAborts[E, S1](failure: => E < S1)(
        using Flat[A]
    ): A < (S & S1 & Aborts[E]) =
        Options.run(effect).map {
            case None    => KYO.fail(failure)
            case Some(a) => a
        }

    def optionsToThrowable(using Flat[A]): A < (S & Aborts[Throwable]) =
        effect.optionsToAborts[Throwable, Any](new NoSuchElementException("None.get"))

    def optionsToUnit(using Flat[A]): A < (S & Aborts[Unit]) =
        effect.optionsToAborts(())

    def optionsToChoices(using Flat[A]): A < (S & Choices) =
        Options.run(effect).map(aOpt => Choices.get(aOpt.toSeq))
end extension

extension [A, S, E](effect: A < (S & Envs[E]))
    def provide[E1, S1, SR](dependency: E1 < S1)(
        using
        fl: Flat[A],
        he: HasEnvs[E1, E] { type Remainder = SR },
        t: Tag[E1],
    ): A < (S & S1 & SR) =
        dependency.map(d => Envs.run[E1, A, S, E, SR](d)(effect))

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
    ): A < (S & S1 & Aborts[E]) =
        Choices.run(effect).map {
            case s if s.isEmpty => KYO.fail[E, S1](error)
            case s              => s.head
        }

    def choicesToThrowable(using Flat[A]): A < (S & Aborts[Throwable]) =
        choicesToAborts[Throwable, Any](new NoSuchElementException("head of empty list"))

    def choicesToUnit(using Flat[A]): A < (S & Aborts[Unit]) =
        choicesToAborts(())
end extension

extension [A, S](effect: A < (S & Fibers))
    def fork(
        using
        @implicitNotFound(
            "Only Fibers- and IOs-based effects can be forked. Found: ${S}"
        ) ev: S => IOs,
        f: Flat[A]
    ): Fiber[A] < (S & IOs) = Fibers.init(effect)

    def forkScoped(
        using
        @implicitNotFound(
            "Only Fibers- and IOs-based effects can be forked. Found: ${S}"
        ) ev: S => IOs,
        f: Flat[A]
    ): Fiber[A] < (S & IOs & Resources) =
        KYO.acquireRelease(Fibers.init(effect))(_.interrupt.discard)
end extension

extension [A, S](fiber: Fiber[A] < S)
    def join: A < (S & Fibers) = Fibers.get(fiber)
    def awaitCompletion(using Flat[A]): Unit < (S & Fibers) =
        KYO.attempt(Fibers.get(fiber))
            .handleAborts
            .discard
end extension

extension [A](effect: A < Fibers)
    @targetName("zipRightPar")
    def &>[A1](next: A1 < Fibers)(
        using
        Flat[A],
        Flat[A1]
    ): A1 < Fibers =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
            _       <- fiberA.awaitCompletion
            a1      <- fiberA1.join
        yield a1

    @targetName("zipLeftPar")
    def <&[A1](next: A1 < Fibers)(
        using
        Flat[A],
        Flat[A1]
    ): A < Fibers =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
            a       <- fiberA.join
            _       <- fiberA1.awaitCompletion
        yield a

    @targetName("zipPar")
    def <&>[A1](next: A1 < Fibers)(
        using
        Flat[A],
        Flat[A1]
    ): (A, A1) < Fibers =
        for
            fiberA  <- effect.fork
            fiberA1 <- next.fork
            a       <- fiberA.join
            a1      <- fiberA1.join
        yield (a, a1)
end extension

extension [A, S](effect: A < (S & Consoles))
    def provideDefaultConsole: A < (S & IOs) = Consoles.run(effect)

final class ProvideAsPartiallyApplied[A, S, E, E1, ER](
    effect: A < (S & Envs[E])
)(
    using
    t: Tag[E1],
    he: HasEnvs[E1, E] { type Remainder = ER },
    f: Flat[A]
):
    def apply[S1](dependency: E1 < S1): A < (S & S1 & ER) =
        dependency.map(d => Envs.run(d)(effect))
end ProvideAsPartiallyApplied


