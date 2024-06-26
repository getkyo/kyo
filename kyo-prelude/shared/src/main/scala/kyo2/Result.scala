package kyo2

import Result.*
import scala.util.Try
import scala.util.control.NonFatal

opaque type Result[+E, +A] >: (Success[A] | Failure[E]) = Success[A] | Cause[E]

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

    def failure[E, A](error: E): Result[E, A] = Failure(error)

    def panic[E, A](exception: Throwable): Result[E, A] = Panic(exception)

    def fromEither[E, T](either: Either[E, T]): Result[E, T] = either match
        case Right(value)    => Success(value)
        case Left(exception) => Failure(exception)

    opaque type Success[+T] = T | SuccessFailure[T]

    object Success:

        def apply[T](value: T): Success[T] =
            value match
                case v: SuccessFailure[?]     => v.nest.asInstanceOf[Success[T]]
                case v: Failure[T] @unchecked => SuccessFailure(v)
                case v                        => v

        // TODO avoid Option allocation
        def unapply[E, T](self: Result[E, T]): Option[T] =
            self.fold(_ => None)(_ => None)(Some(_))

    end Success

    sealed abstract class Cause[+E]

    case class Failure[+E](error: E) extends Cause[E]

    object Failure:
        def unapply[E, A](result: Result[E, A]): Option[E] =
            (result: @unchecked) match
                case result: Failure[E] => Some(result.error)
                case _                  => None
    end Failure

    case class Panic(exception: Throwable) extends Cause[Nothing]

    extension [E, T](self: Result[E, T])

        def isSuccess: Boolean =
            self match
                case _: Cause[?] => false
                case _           => true

        def isFailure: Boolean =
            self.isInstanceOf[Failure[?]]

        def isPanic: Boolean =
            self.isInstanceOf[Panic]

        inline def fold[U](inline ifFailure: E => U)(inline ifPanic: Throwable => U)(inline ifSuccess: T => U): U =
            (self: @unchecked) match
                case self: Failure[E] =>
                    ifFailure(self.error)
                case self: Panic =>
                    ifPanic(self.exception)
                case _ =>
                    try
                        self match
                            case self: SuccessFailure[T] @unchecked =>
                                ifSuccess(self.unnest.asInstanceOf[T])
                            case self =>
                                ifSuccess(self.asInstanceOf[T])
                    catch
                        case ex if NonFatal(ex) => ifPanic(ex)

        def get(using E =:= Nothing): T =
            fold(ex => throw new NoSuchElementException(s"Cause: $ex"))(throw _)(identity)

        inline def getOrElse[U >: T](inline default: => U): U =
            fold(_ => default)(_ => default)(identity)

        def orElse[E2, U >: T](alternative: => Result[E2, U]): Result[E | E2, U] =
            fold(_ => alternative)(_ => alternative)(v => Result.success(v))

        inline def flatMap[E2, U](inline f: T => Result[E2, U]): Result[E | E2, U] =
            (self: @unchecked) match
                case self: Cause[E] => self
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
                    Panic(new NoSuchElementException("Predicate does not hold for " + v))
                else
                    v
            }

        inline def recover[U >: T](inline pf: PartialFunction[Cause[E], U]): Result[E, U] =
            try
                (self: @unchecked) match
                    case self: Cause[E] if pf.isDefinedAt(self) =>
                        Result.success(pf(self))
                    case _ => self
            catch
                case ex: Throwable if NonFatal(ex) =>
                    Panic(ex)

        inline def recoverWith[E2, U >: T](inline pf: PartialFunction[Cause[E], Result[E2, U]]): Result[E | E2, U] =
            try
                (self: @unchecked) match
                    case self: Cause[E] if pf.isDefinedAt(self) =>
                        pf(self)
                    case _ => self
            catch
                case ex: Throwable if NonFatal(ex) =>
                    Panic(ex)

        def toEither: Either[E | Throwable, T] =
            fold(Left(_))(Left(_))(Right(_))

        def toTry: Try[T] =
            fold(e => scala.util.Failure(new NoSuchElementException(s"Cause: $e")))(scala.util.Failure(_))(scala.util.Success(_))
    end extension

    private object internal:
        case class SuccessFailure[+T](cause: Cause[T], depth: Int = 1):
            def unnest: Result[Any, T] =
                if depth > 1 then
                    SuccessFailure(cause, depth - 1)
                else
                    cause
            def nest: Success[T] =
                SuccessFailure(cause, depth + 1)
        end SuccessFailure
    end internal
end Result
