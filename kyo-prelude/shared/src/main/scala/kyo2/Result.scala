package kyo2

import Result.*
import scala.annotation.implicitNotFound
import scala.util.Try
import scala.util.control.NonFatal

opaque type Result[+E, +A] >: (Success[A] | Failure[E]) = Success[A] | Failure[E]

object Result:
    import internal.*

    given [E, A](using CanEqual[A, A]): CanEqual[Result[E, A], Result[E, A]] = CanEqual.derived
    given [E, A]: CanEqual[Result[E, A], Panic]                              = CanEqual.derived

    def apply[A](expr: => A): Result[Nothing, A] =
        try
            Success(expr)
        catch
            case ex: Throwable if NonFatal(ex) => Panic(ex)

    def attempt[A](expr: => A): Result[Throwable, A] =
        try
            Success(expr)
        catch
            case ex: Throwable if NonFatal(ex) => Error(ex)

    def success[E, A](value: A): Result[E, A]           = Success(value)
    def error[E, A](error: E): Result[E, A]             = Error(error)
    def panic[E, A](exception: Throwable): Result[E, A] = Panic(exception)

    def fromEither[E, A](either: Either[E, A]): Result[E, A] =
        either match
            case Right(value) => Success(value)
            case Left(error)  => Error(error)

    opaque type Success[+A] = A | SuccessError[A]

    object Success:

        def apply[A](value: A): Success[A] =
            value match
                case v: SuccessError[?]     => v.nest.asInstanceOf[Success[A]]
                case v: Error[A] @unchecked => SuccessError(v)
                case v                      => v

        // TODO avoid Option allocation
        def unapply[E, A](self: Result[E, A]): Option[A] =
            self.fold(_ => None)(_ => None)(Some(_))

    end Success

    sealed abstract class Failure[+E]

    case class Error[+E](error: E) extends Failure[E]

    object Error:
        def unapply[E, A](result: Result[E, A]): Option[E] =
            (result: @unchecked) match
                case result: Error[E] => Some(result.error)
                case _                => None
    end Error

    case class Panic(exception: Throwable) extends Failure[Nothing]

    extension [E, A](self: Result[E, A])

        def isSuccess: Boolean =
            self match
                case _: Failure[?] => false
                case _             => true

        def isError: Boolean =
            self.isInstanceOf[Error[?]]

        def isPanic: Boolean =
            self.isInstanceOf[Panic]

        inline def fold[B](inline ifError: E => B)(inline ifPanic: Throwable => B)(inline ifSuccess: A => B): B =
            (self: @unchecked) match
                case self: Error[E] =>
                    ifError(self.error)
                case self: Panic =>
                    ifPanic(self.exception)
                case _ =>
                    try
                        self match
                            case self: SuccessError[A] @unchecked =>
                                ifSuccess(self.unnest.asInstanceOf[A])
                            case self =>
                                ifSuccess(self.asInstanceOf[A])
                    catch
                        case ex if NonFatal(ex) => ifPanic(ex)

        def get(using E =:= Nothing): A =
            fold(ex => throw new NoSuchElementException(s"Failure: $ex"))(throw _)(identity)

        inline def getOrElse[B >: A](inline default: => B): B =
            fold(_ => default)(_ => default)(identity)

        def orElse[E2, B >: A](alternative: => Result[E2, B]): Result[E | E2, B] =
            fold(_ => alternative)(_ => alternative)(Result.success)

        inline def flatMap[E2, B](inline f: A => Result[E2, B]): Result[E | E2, B] =
            (self: @unchecked) match
                case self: Failure[E] => self
                case self =>
                    try f(self.asInstanceOf[Success[A]].get)
                    catch
                        case ex if NonFatal(ex) =>
                            Panic(ex)

        def flatten[E2, B](using ev: A <:< Result[E2, B]): Result[E | E2, B] =
            flatMap(ev)

        inline def map[B](inline f: A => B): Result[E, B] =
            flatMap(v => Result.success(f(v)))

        inline def withFilter(inline p: A => Boolean): Result[E | NoSuchElementException, A] =
            filter(p)

        inline def filter(inline p: A => Boolean): Result[E | NoSuchElementException, A] =
            flatMap { v =>
                if !p(v) then
                    Error(new NoSuchElementException("Predicate does not hold for " + v))
                else
                    v
            }

        inline def recover[B >: A](inline pf: PartialFunction[Failure[E], B]): Result[E, B] =
            try
                (self: @unchecked) match
                    case self: Failure[E] if pf.isDefinedAt(self) =>
                        Result.success(pf(self))
                    case _ => self
            catch
                case ex: Throwable if NonFatal(ex) =>
                    Panic(ex)

        inline def recoverWith[E2, B >: A](inline pf: PartialFunction[Failure[E], Result[E2, B]]): Result[E | E2, B] =
            try
                (self: @unchecked) match
                    case self: Failure[E] if pf.isDefinedAt(self) =>
                        pf(self)
                    case _ => self
            catch
                case ex: Throwable if NonFatal(ex) =>
                    Panic(ex)

        def toEither: Either[E | Throwable, A] =
            fold(Left(_))(Left(_))(Right(_))

        def toTry(using
            @implicitNotFound("Error type must be a 'Throwable' to invoke 'toTry'. Found: '${E}'")
            ev: E <:< Throwable
        ): Try[A] =
            fold(e => scala.util.Failure(ev(e)))(scala.util.Failure(_))(scala.util.Success(_))

    end extension

    private object internal:
        case class SuccessError[+A](failure: Failure[A], depth: Int = 1):
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
