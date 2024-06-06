package kyo

import Result.*
import kyo.Flat
import scala.util.control.NonFatal

opaque type Result[+T] >: (Success[T] | Failure[T]) = Success[T] | Failure[T]

object Result:
    import internal.*

    given [T: Flat]: Flat[Result[T]]                                = Flat.unsafe.bypass
    given [T](using CanEqual[T, T]): CanEqual[Result[T], Result[T]] = CanEqual.derived

    def apply[T](expr: => T): Result[T] =
        try
            Success(expr)
        catch
            case ex: Throwable if NonFatal(ex) => Failure(ex)

    def success[T](value: T): Result[T] = Success(value)

    def failure[T](exception: Throwable): Result[T] = Failure(exception)

    def fromEither[T](either: Either[Throwable, T]): Result[T] = either match
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
        def unapply[T](self: Result[T]): Option[T] =
            (self: @unchecked) match
                case _: Failure[?] => None
                case s: SuccessFailure[?] =>
                    Some(s.unnest.asInstanceOf[T])
                case s: T @unchecked => Some(s)

    end Success

    case class Failure[+T](exception: Throwable)

    object Failure:

        // TODO avoid Option allocation
        def unapply[T](self: Result[T]): Option[Throwable] =
            self match
                case f: Failure[?] => Some(f.exception)
                case _             => None
    end Failure

    extension [T](self: Result[T])
        inline def isSuccess: Boolean =
            self match
                case _: Failure[?] => false
                case _             => true

        inline def isFailure: Boolean = !isSuccess

        inline def get: T =
            (self: @unchecked) match
                case Success(value)     => value
                case Failure(exception) => throw exception

        inline def getOrElse[U >: T](inline default: => U): U =
            (self: @unchecked) match
                case Success(value) => value
                case Failure(_)     => default

        inline def orElse[U >: T](inline alternative: => Result[U]): Result[U] =
            (self: @unchecked) match
                case Success(value) => self
                case Failure(_)     => alternative

        inline def flatMap[U](inline f: T => Result[U]): Result[U] =
            (self: @unchecked) match
                case Success(value) => Result(f(value)).flatten
                case failure        => failure.asInstanceOf[Result[U]]

        inline def flatten[U](using ev: T <:< Result[U]): Result[U] =
            (self: @unchecked) match
                case Success(value) => ev(value)
                case failure        => failure.asInstanceOf[Result[U]]

        inline def map[U](inline f: T => U): Result[U] =
            (self: @unchecked) match
                case Success(value) => Result(f(value))
                case failure        => failure.asInstanceOf[Result[U]]

        inline def fold[U](inline ifFailure: Throwable => U)(inline ifSuccess: T => U): U =
            (self: @unchecked) match
                case Success(value) =>
                    try ifSuccess(value)
                    catch
                        case ex if NonFatal(ex) =>
                            ifFailure(ex)
                case Failure(exception) => ifFailure(exception)

        inline def filter(inline p: T => Boolean): Result[T] =
            (self: @unchecked) match
                case Success(value) if p(value) => self
                case Success(value)             => Failure(new NoSuchElementException("Predicate does not hold for " + value))
                case failure                    => self

        inline def recover[U >: T](inline rescueException: PartialFunction[Throwable, U]): Result[U] =
            (self: @unchecked) match
                case Success(value) => self
                case Failure(exception) if rescueException.isDefinedAt(exception) =>
                    Result(rescueException(exception))
                case failure => failure.asInstanceOf[Result[U]]

        inline def recoverWith[U >: T](inline rescueException: PartialFunction[Throwable, Result[U]]): Result[U] =
            (self: @unchecked) match
                case Success(value) => self
                case Failure(exception) if rescueException.isDefinedAt(exception) =>
                    rescueException(exception)
                case failure => failure.asInstanceOf[Result[U]]

        inline def toEither: Either[Throwable, T] =
            (self: @unchecked) match
                case Success(value)     => Right(value)
                case Failure(exception) => Left(exception)
    end extension

    private object internal:
        case class SuccessFailure[+T](failure: Failure[T], depth: Int = 1):
            def unnest: Result[T] =
                if depth > 1 then
                    SuccessFailure(failure, depth - 1)
                else
                    failure
            def nest: Success[T] =
                SuccessFailure(failure, depth + 1)
        end SuccessFailure
    end internal
end Result
