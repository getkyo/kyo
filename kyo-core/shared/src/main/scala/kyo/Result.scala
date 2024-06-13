package kyo

import Result.*
import kyo.Flat
import scala.util.Try
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
            self.fold(_ => None)(Some(_))

    end Success

    case class Failure[+T](exception: Throwable)

    object Failure:

        // TODO avoid Option allocation
        def unapply[T](self: Result[T]): Option[Throwable] =
            self.fold(Some(_))(_ => None)
    end Failure

    extension [T](self: Result[T])
        inline def isSuccess: Boolean =
            self match
                case _: Failure[?] => false
                case _             => true

        inline def isFailure: Boolean = !isSuccess

        inline def fold[U](inline ifFailure: Throwable => U)(inline ifSuccess: T => U): U =
            (self: @unchecked) match
                case self: Failure[T] =>
                    ifFailure(self.exception)
                case _ =>
                    try
                        self match
                            case self: SuccessFailure[T] @unchecked =>
                                ifSuccess(self.unnest.asInstanceOf[T])
                            case self =>
                                ifSuccess(self.asInstanceOf[T])
                    catch
                        case ex if NonFatal(ex) => ifFailure(ex)

        inline def get: T =
            fold(ex => throw ex)(v => v)

        inline def getOrElse[U >: T](inline default: => U): U =
            fold(_ => default)(v => v)

        def orElse[U >: T](alternative: => Result[U]): Result[U] =
            fold(_ => alternative)(v => Result.success(v))

        inline def flatMap[U](inline f: T => Result[U]): Result[U] =
            (self: @unchecked) match
                case _: Failure[?] =>
                    self.asInstanceOf[Result[U]]
                case _ =>
                    try f(self.get)
                    catch
                        case ex if NonFatal(ex) =>
                            Failure(ex)

        inline def flatten[U](using ev: T <:< Result[U]): Result[U] =
            flatMap(ev)

        inline def map[U](inline f: T => U): Result[U] =
            flatMap(v => Result.success(f(v)))

        inline def withFilter(inline p: T => Boolean): Result[T] =
            filter(p)

        inline def filter(inline p: T => Boolean): Result[T] =
            flatMap { v =>
                if !p(v) then
                    throw new NoSuchElementException("Predicate does not hold for " + v)
                v
            }

        inline def recover[U >: T](inline rescueException: PartialFunction[Throwable, U]): Result[U] =
            (self: @unchecked) match
                case Failure(exception) if rescueException.isDefinedAt(exception) =>
                    Result(rescueException(exception))
                case _ => self.asInstanceOf[Result[U]]

        inline def recoverWith[U >: T](inline rescueException: PartialFunction[Throwable, Result[U]]): Result[U] =
            (self: @unchecked) match
                case Failure(exception) if rescueException.isDefinedAt(exception) =>
                    rescueException(exception)
                case _ => self.asInstanceOf[Result[U]]

        inline def toEither: Either[Throwable, T] =
            fold(Left(_))(Right(_))

        inline def toTry: Try[T] =
            fold(scala.util.Failure(_))(scala.util.Success(_))
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
