package kyo2

import Result.*
import scala.annotation.implicitNotFound
import scala.annotation.targetName
import scala.util.Try
import scala.util.control.NonFatal

opaque type Result[+E, +A] >: (Success[A] | Error[E]) = Success[A] | Error[E]

object Result:
    import internal.*

    inline given [E, A](using inline ce: CanEqual[A, A]): CanEqual[Result[E, A], Result[E, A]] = CanEqual.derived
    given [E, A]: CanEqual[Result[E, A], Panic]                                                = CanEqual.derived

    inline def apply[A](expr: => A): Result[Nothing, A] =
        try
            Success(expr)
        catch
            case ex => Panic(ex)

    inline def attempt[A](expr: => A): Result[Throwable, A] =
        try
            Success(expr)
        catch
            case ex if NonFatal(ex) => Fail(ex)

    inline def success[E, A](inline value: A): Result[E, A]           = Success(value)
    inline def fail[E, A](inline error: E): Result[E, A]              = Fail(error)
    inline def panic[E, A](inline exception: Throwable): Result[E, A] = Panic(exception)

    private val _unit            = Success(())
    def unit[E]: Result[E, Unit] = _unit

    def fromEither[E, A](either: Either[E, A]): Result[E, A] =
        either.fold(fail, success)

    def fromTry[A](t: Try[A]): Result[Throwable, A] =
        t.fold(fail, success)

    opaque type Success[+A] = A | SuccessError[A]

    object Success:

        def apply[A](value: A): Success[A] =
            value match
                case v: SuccessError[?]    => v.nest.asInstanceOf[Success[A]]
                case v: Fail[A] @unchecked => SuccessError(v)
                case v                     => v

        def unapply[E, A](self: Result[E, A]): Maybe.Ops[A] =
            self.fold(_ => Maybe.empty)(Maybe(_))

    end Success

    sealed abstract class Error[+E]:
        def getFailure: E | Throwable

    case class Fail[+E](error: E) extends Error[E]:
        def getFailure = error

    object Fail:
        def unapply[E, A](result: Result[E, A]): Maybe.Ops[E] =
            result match
                case result: Fail[E] @unchecked =>
                    Maybe(result.error)
                case _ => Maybe.empty
    end Fail

    case class Panic(exception: Throwable) extends Error[Nothing]:
        def getFailure = exception

    object Panic:
        def apply(exception: Throwable): Panic =
            if NonFatal(exception) then
                new Panic(exception)
            else
                throw exception
    end Panic

    extension [E, A](self: Result[E, A])

        def isSuccess: Boolean =
            self match
                case _: Error[?] => false
                case _           => true

        def isFail =
            self.isInstanceOf[Fail[?]]

        def isPanic: Boolean =
            self.isInstanceOf[Panic]

        def value: Maybe[A] =
            self match
                case self: Error[?] => Maybe.empty
                case self           => Maybe(self.asInstanceOf[A])

        @targetName("maybeError")
        def failure: Maybe[E] =
            self match
                case self: Fail[E] @unchecked => Maybe(self.error)
                case _                        => Maybe.empty

        @targetName("maybePanic")
        def panic: Maybe[Throwable] =
            self match
                case self: Panic => Maybe(self.exception)
                case _           => Maybe.empty

        inline def fold[B](inline ifFailure: Error[E] => B)(inline ifSuccess: A => B): B =
            self match
                case self: Error[E] @unchecked => ifFailure(self)
                case _ =>
                    try ifSuccess(self.asInstanceOf[Result[Nothing, A]].get)
                    catch
                        case ex => ifFailure(Panic(ex))

        def get(
            using
            @implicitNotFound("Can't get result due to pending error: '${E}'")
            ev: E =:= Nothing
        ): A =
            self match
                case self: Fail[E] @unchecked => throw new NoSuchElementException(s"Error: ${self.error}")
                case self: Panic              => throw self.exception
                case self: SuccessError[?]    => self.unnest.asInstanceOf[A]
                case self                     => self.asInstanceOf[A]
            end match
        end get

        inline def getOrElse[B >: A](inline default: => B): B =
            fold(_ => default)(identity)

        def orElse[E2, B >: A](alternative: => Result[E2, B]): Result[E | E2, B] =
            fold(_ => alternative)(Result.success)

        inline def flatMap[E2, B](inline f: A => Result[E2, B]): Result[E | E2, B] =
            self match
                case self: Error[E] @unchecked => self
                case self =>
                    try f(self.asInstanceOf[Success[A]].get)
                    catch
                        case ex => Panic(ex)

        def flatten[E2, B](using ev: A <:< Result[E2, B]): Result[E | E2, B] =
            flatMap(ev)

        inline def map[B](inline f: A => B): Result[E, B] =
            flatMap(v => Result.success(f(v)))

        inline def withFilter(inline p: A => Boolean): Result[E | NoSuchElementException, A] =
            filter(p)

        inline def filter(inline p: A => Boolean): Result[E | NoSuchElementException, A] =
            flatMap { v =>
                if !p(v) then
                    Fail(new NoSuchElementException("Predicate does not hold for " + v))
                else
                    v
            }

        inline def recover[B >: A](pf: PartialFunction[Error[E], B]): Result[E, B] =
            try
                self match
                    case self: Error[E] @unchecked if pf.isDefinedAt(self) =>
                        Result.success(pf(self))
                    case _ => self
            catch
                case ex => Panic(ex)

        inline def recoverWith[E2, B >: A](pf: PartialFunction[Error[E], Result[E2, B]]): Result[E | E2, B] =
            try
                self match
                    case self: Error[E] @unchecked if pf.isDefinedAt(self) =>
                        pf(self)
                    case _ => self
            catch
                case ex => Panic(ex)

        def toEither: Either[E | Throwable, A] =
            fold(e => Left(e.getFailure))(Right(_))

        def toTry(using
            @implicitNotFound("Fail type must be a 'Throwable' to invoke 'toTry'. Found: '${E}'")
            ev: E <:< Throwable
        ): Try[A] =
            fold(e => scala.util.Failure(e.getFailure.asInstanceOf[Throwable]))(scala.util.Success(_))

    end extension

    private object internal:
        case class SuccessError[+A](failure: Error[A], depth: Int = 1):
            def unnest: Result[Any, A] =
                if depth > 1 then
                    SuccessError(failure, depth - 1)
                else
                    failure
            def nest: Success[A] =
                SuccessError(failure, depth + 1)
        end SuccessError
    end internal
end Result
