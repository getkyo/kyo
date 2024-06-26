package kyo2

import kyo2.Result.*
import scala.util.Try
import scala.util.control.NonFatal

opaque type Result[+E, +A] >: (Success[A] | Failure[E]) = Success[A] | Failure[E]

object Result:
    given [E1, A1, E2, A2](using CanEqual[A1, A2]): CanEqual[Result[E1, A1], Result[E2, A2]] = CanEqual.derived

    opaque type Success[+A] = A

    sealed abstract class Failure[+E]
    case class Cause[+E](error: E)         extends Failure[E]
    case class Panic(exception: Throwable) extends Failure[Nothing]

    def apply[A](expr: => A): Result[Nothing, A] =
        try success(expr)
        catch case ex if NonFatal(ex) => panic(ex)

    def success[E, A](value: A): Result[E, A]           = value
    def failure[E, A](error: E): Result[E, A]           = Cause(error)
    def panic[E, A](exception: Throwable): Result[E, A] = Panic(exception)

    extension [E, A](self: Result[E, A])
        inline def fold[B](inline ifCause: E => B, inline ifPanic: Throwable => B, inline ifSuccess: A => B): B =
            self match
                case self: Failure[E] @unchecked =>
                    self match
                        case Cause(error)     => ifCause(error)
                        case Panic(exception) => ifPanic(exception)
                case _ => ifSuccess(self.asInstanceOf[A])

        inline def isSuccess: Boolean = !self.isInstanceOf[Failure[?]]
        inline def isFailure: Boolean = self.isInstanceOf[Failure[?]]
        inline def isCause: Boolean   = self.isInstanceOf[Cause[?]]
        inline def isPanic: Boolean   = self.isInstanceOf[Panic]

        inline def flatMap[EE >: E, B](inline f: A => Result[EE, B]): Result[EE, B] =
            self match
                case self: Failure[E] => self
                case _ =>
                    try f(self.asInstanceOf[A])
                    catch case ex if NonFatal(ex) => panic(ex)

        inline def map[B](inline f: A => B): Result[E, B] =
            self match
                case self: Failure[E] => self
                case _ =>
                    try success(f(self.asInstanceOf[A]))
                    catch case ex if NonFatal(ex) => panic(ex)

        inline def mapError[EE](inline f: E => EE): Result[EE, A] =
            self match
                case Cause(e: E) => failure(f(e))
                case _           => self.asInstanceOf[Result[EE, A]]

        inline def get: A =
            self match
                case Cause(e)  => throw new Exception(e.toString)
                case Panic(ex) => throw ex
                case _         => self.asInstanceOf[A]

        inline def getOrElse[B >: A](inline default: => B): B =
            if isSuccess then self.asInstanceOf[B] else default

        inline def orElse[EE >: E, B >: A](inline that: => Result[EE, B]): Result[EE, B] =
            if isSuccess then self.asInstanceOf[Result[EE, B]] else that

        inline def recover[B >: A](inline pf: PartialFunction[E, B]): Result[E, B] =
            self match
                case Cause(e: E) if pf.isDefinedAt(e) =>
                    try success(pf(e))
                    catch case ex if NonFatal(ex) => panic(ex)
                case _ => self

        inline def recoverWith[EE >: E, B >: A](inline pf: PartialFunction[E, Result[EE, B]]): Result[EE, B] =
            self match
                case Cause(e: E) if pf.isDefinedAt(e) =>
                    try pf(e)
                    catch case ex if NonFatal(ex) => panic(ex)
                case _ => self

        def toEither: Either[E, A] = fold(Left(_), throw _, Right(_))

        def toTry: Try[A] = fold(
            e => scala.util.Failure(new NoSuchElementException(s"Cause: $e")),
            scala.util.Failure(_),
            scala.util.Success(_)
        )
    end extension

end Result
