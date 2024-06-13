package kyo2

import Result.*
import scala.util.Try
import scala.util.control.NonFatal

opaque type Result[+A] >: (Success[A] | Failure[A]) = Success[A] | Failure[A]

object Result:
    import internal.*

    given [A](using CanEqual[A, A]): CanEqual[Result[A], Result[A]] = CanEqual.derived

    def apply[A](expr: => A): Result[A] =
        try
            Success(expr)
        catch
            case ex: Throwable if NonFatal(ex) => Failure(ex)

    def success[A](value: A): Result[A] = Success(value)

    def failure[A](exception: Throwable): Result[A] = Failure(exception)

    def fromEither[A](either: Either[Throwable, A]): Result[A] = either match
        case Right(value)    => Success(value)
        case Left(exception) => Failure(exception)

    opaque type Success[+A] = A | SuccessFailure[A]

    object Success:

        def apply[A](value: A): Success[A] =
            value match
                case v: SuccessFailure[?]     => v.nest.asInstanceOf[Success[A]]
                case v: Failure[A] @unchecked => SuccessFailure(v)
                case v                        => v

        // TODO avoid Option allocation
        def unapply[A](self: Result[A]): Option[A] =
            self.fold(_ => None)(Some(_))

    end Success

    case class Failure[+A](exception: Throwable)

    object Failure:

        // TODO avoid Option allocation
        def unapply[A](self: Result[A]): Option[Throwable] =
            self.fold(Some(_))(_ => None)
    end Failure

    extension [A](self: Result[A])
        inline def isSuccess: Boolean =
            self match
                case _: Failure[?] => false
                case _             => true

        inline def isFailure: Boolean = !isSuccess

        inline def fold[B](inline ifFailure: Throwable => B)(inline ifSuccess: A => B): B =
            (self: @unchecked) match
                case self: Failure[A] =>
                    ifFailure(self.exception)
                case _ =>
                    try
                        self match
                            case self: SuccessFailure[A] @unchecked =>
                                ifSuccess(self.unnest.asInstanceOf[A])
                            case self =>
                                ifSuccess(self.asInstanceOf[A])
                    catch
                        case ex if NonFatal(ex) => ifFailure(ex)

        inline def get: A =
            fold(ex => throw ex)(v => v)

        inline def getOrElse[B >: A](inline default: => B): B =
            fold(_ => default)(v => v)

        def orElse[B >: A](alternative: => Result[B]): Result[B] =
            fold(_ => alternative)(v => Result.success(v))

        inline def flatMap[B](inline f: A => Result[B]): Result[B] =
            (self: @unchecked) match
                case _: Failure[?] =>
                    self.asInstanceOf[Result[B]]
                case _ =>
                    try f(self.get)
                    catch
                        case ex if NonFatal(ex) =>
                            Failure(ex)

        inline def flatten[B](using ev: A <:< Result[B]): Result[B] =
            flatMap(ev)

        inline def map[B](inline f: A => B): Result[B] =
            flatMap(v => Result.success(f(v)))

        inline def filter(inline p: A => Boolean): Result[A] =
            flatMap { v =>
                if !p(v) then
                    throw new NoSuchElementException("Predicate does not hold for " + v)
                v
            }

        inline def recover[B >: A](inline rescueException: PartialFunction[Throwable, B]): Result[B] =
            (self: @unchecked) match
                case Failure(exception) if rescueException.isDefinedAt(exception) =>
                    Result(rescueException(exception))
                case _ => self.asInstanceOf[Result[B]]

        inline def recoverWith[B >: A](inline rescueException: PartialFunction[Throwable, Result[B]]): Result[B] =
            (self: @unchecked) match
                case Failure(exception) if rescueException.isDefinedAt(exception) =>
                    rescueException(exception)
                case _ => self.asInstanceOf[Result[B]]

        inline def toEither: Either[Throwable, A] =
            fold(Left(_))(Right(_))

        inline def toTry: Try[A] =
            fold(scala.util.Failure(_))(scala.util.Success(_))
    end extension

    private object internal:
        case class SuccessFailure[+A](failure: Failure[A], depth: Int = 1):
            def unnest: Result[A] =
                if depth > 1 then
                    SuccessFailure(failure, depth - 1)
                else
                    failure
            def nest: Success[A] =
                SuccessFailure(failure, depth + 1)
        end SuccessFailure
    end internal
end Result
