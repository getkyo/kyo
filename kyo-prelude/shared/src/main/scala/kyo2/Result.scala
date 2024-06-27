package kyo2

import Result.*
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

    def success[E, A](value: A): Result[E, A] = Success(value)

    def error[E, A](error: E): Result[E, A] = Error(error)

    def panic[E, A](exception: Throwable): Result[E, A] = Panic(exception)

    def fromEither[E, A](either: Either[E, A]): Result[E, A] = either match
        case Right(value) => Success(value)
        case Left(error)  => Error(error)

    opaque type Success[+T] = T | SuccessError[T]

    object Success:

        def apply[T](value: T): Success[T] =
            value match
                case v: SuccessError[?]     => v.nest.asInstanceOf[Success[T]]
                case v: Error[T] @unchecked => SuccessError(v)
                case v                      => v

        // TODO avoid Option allocation
        def unapply[E, T](self: Result[E, T]): Option[T] =
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

    extension [E, T](self: Result[E, T])

        def isSuccess: Boolean =
            self match
                case _: Failure[?] => false
                case _             => true

        def isError: Boolean =
            self.isInstanceOf[Error[?]]

        def isPanic: Boolean =
            self.isInstanceOf[Panic]

        inline def fold[U](inline ifError: E => U)(inline ifPanic: Throwable => U)(inline ifSuccess: T => U): U =
            (self: @unchecked) match
                case self: Error[E] =>
                    ifError(self.error)
                case self: Panic =>
                    ifPanic(self.exception)
                case _ =>
                    try
                        self match
                            case self: SuccessError[T] @unchecked =>
                                ifSuccess(self.unnest.asInstanceOf[T])
                            case self =>
                                ifSuccess(self.asInstanceOf[T])
                    catch
                        case ex if NonFatal(ex) => ifPanic(ex)

        def get(using E =:= Nothing): T =
            fold(ex => throw new NoSuchElementException(s"Failure: $ex"))(throw _)(identity)

        inline def getOrElse[U >: T](inline default: => U): U =
            fold(_ => default)(_ => default)(identity)

        def orElse[E2, U >: T](alternative: => Result[E2, U]): Result[E | E2, U] =
            fold(_ => alternative)(_ => alternative)(Result.success)

        inline def flatMap[E2, U](inline f: T => Result[E2, U]): Result[E | E2, U] =
            (self: @unchecked) match
                case self: Failure[E] => self
                case self =>
                    try f(self.asInstanceOf[Success[T]].get)
                    catch
                        case ex if NonFatal(ex) =>
                            Panic(ex)

        def flatten[E2, U](using ev: T <:< Result[E2, U]): Result[E | E2, U] =
            flatMap(ev)

        inline def map[U](inline f: T => U): Result[E, U] =
            flatMap(v => Result.success(f(v)))

        inline def withFilter(inline p: T => Boolean): Result[E | NoSuchElementException, T] =
            filter(p)

        inline def filter(inline p: T => Boolean): Result[E | NoSuchElementException, T] =
            flatMap { v =>
                if !p(v) then
                    Error(new NoSuchElementException("Predicate does not hold for " + v))
                else
                    v
            }

        inline def recover[U >: T](inline pf: PartialFunction[Failure[E], U]): Result[E, U] =
            try
                (self: @unchecked) match
                    case self: Failure[E] if pf.isDefinedAt(self) =>
                        Result.success(pf(self))
                    case _ => self
            catch
                case ex: Throwable if NonFatal(ex) =>
                    Panic(ex)

        inline def recoverWith[E2, U >: T](inline pf: PartialFunction[Failure[E], Result[E2, U]]): Result[E | E2, U] =
            try
                (self: @unchecked) match
                    case self: Failure[E] if pf.isDefinedAt(self) =>
                        pf(self)
                    case _ => self
            catch
                case ex: Throwable if NonFatal(ex) =>
                    Panic(ex)

        def toEither: Either[E | Throwable, T] =
            fold(Left(_))(Left(_))(Right(_))

        def toTry: Try[T] =
            fold(e => scala.util.Failure(new NoSuchElementException(s"Failure: $e")))(scala.util.Failure(_))(scala.util.Success(_))
    end extension

    private object internal:
        case class SuccessError[+T](failure: Failure[T], depth: Int = 1):
            def unnest: Result[Any, T] =
                if depth > 1 then
                    SuccessError(failure, depth - 1)
                else
                    failure
            def nest: Success[T] =
                SuccessError(failure, depth + 1)
        end SuccessError
    end internal
end Result
