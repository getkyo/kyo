package kyo

import Result.*
import scala.annotation.implicitNotFound
import scala.annotation.targetName
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.NonFatal

/** Represents a result that can be either a success, a failure or an unexpected panic.
  *
  * @tparam E
  *   The type of the error in case of failure
  * @tparam A
  *   The type of the value in case of success
  */
opaque type Result[+E, +A] >: (Success[A] | Error[E]) = Success[A] | Error[E]

/** Companion object for Result type */
object Result:
    import internal.*

    inline given [E, A](using inline ce: CanEqual[A, A]): CanEqual[Result[E, A], Result[E, A]] = CanEqual.derived
    inline given [E, A]: CanEqual[Result[E, A], Panic]                                         = CanEqual.derived

    /** Creates a Result from an expression that might throw an exception.
      *
      * @param expr
      *   The expression to evaluate
      * @tparam A
      *   The type of the successful result
      * @return
      *   A Result containing either the successful value or a Panic with the caught exception
      */
    inline def apply[A](inline expr: => A): Result[Nothing, A] =
        try
            Success(expr)
        catch
            case ex => Panic(ex)

    class CatchingOps[E <: Throwable](dummy: Unit) extends AnyVal:
        /** Evaluates an expression, catching a specific exception type.
          *
          * @param expr
          *   The expression to evaluate
          * @tparam A
          *   The type of the successful result
          * @return
          *   A Result containing either the successful value, a Fail with the caught exception, or a Panic for other exceptions
          */
        inline def apply[A](inline expr: => A)(using ct: SafeClassTag[E]): Result[E, A] =
            try
                Success(expr)
            catch
                case ct(ex) => Fail(ex)
                case ex     => Panic(ex)
    end CatchingOps

    inline def catching[E <: Throwable]: CatchingOps[E] = CatchingOps(())

    /** Creates a successful Result.
      *
      * @param value
      *   The successful value
      * @tparam E
      *   The type of the error (not used in this case)
      * @tparam A
      *   The type of the successful value
      * @return
      *   A successful Result
      */
    inline def success[E, A](inline value: A): Result[E, A] = Success(value)

    /** Creates a failed Result.
      *
      * @param error
      *   The error value
      * @tparam E
      *   The type of the error
      * @tparam A
      *   The type of the successful value (not used in this case)
      * @return
      *   A failed Result
      */
    inline def fail[E, A](inline error: E): Result[E, A] = Fail(error)

    /** Creates a Result representing a panic situation.
      *
      * @param exception
      *   The exception causing the panic
      * @tparam E
      *   The type of the error (not used in this case)
      * @tparam A
      *   The type of the successful value (not used in this case)
      * @return
      *   A Result in a panic state
      */
    inline def panic[E, A](inline exception: Throwable): Result[E, A] = Panic(exception)

    /** Collects a sequence of Results into a single Result containing a sequence of successful values.
      *
      * @param seq
      *   The sequence of Results to collect
      * @tparam E
      *   The type of the error
      * @tparam A
      *   The type of the successful values
      * @return
      *   A Result containing either a sequence of successful values or the first encountered error
      */
    def collect[E, A](seq: Seq[Result[E, A]]): Result[E, Seq[A]] =
        def loop(remaining: Seq[Result[E, A]], acc: Chunk[A]): Result[E, Chunk[A]] =
            remaining match
                case (head: Result[E, A]) +: tail =>
                    head.fold(error => error)(value => loop(tail, acc.append(value)))
                case Seq() => Success(acc)

        loop(seq, Chunk.empty[A])
    end collect

    private val _unit = Success(())

    /** Returns a successful Result containing unit.
      *
      * @tparam E
      *   The type of the error (not used in this case)
      * @return
      *   A successful Result containing unit
      */
    def unit[E]: Result[E, Unit] = _unit

    /** Converts an Either to a Result.
      *
      * @param either
      *   The Either to convert
      * @tparam E
      *   The type of the error (Left value)
      * @tparam A
      *   The type of the successful value (Right value)
      * @return
      *   A Result equivalent to the input Either
      */
    def fromEither[E, A](either: Either[E, A]): Result[E, A] =
        either.fold(fail, success)

    /** Converts a Try to a Result.
      *
      * @param t
      *   The Try to convert
      * @tparam A
      *   The type of the successful value
      * @return
      *   A Result equivalent to the input Try
      */
    def fromTry[A](t: Try[A]): Result[Throwable, A] =
        t.fold(fail, success)

    /** Represents a successful Result. */
    opaque type Success[+A] = A | SuccessError[A]

    /** Companion object for Success type */
    object Success:
        /** Creates a Success instance.
          *
          * @param value
          *   The successful value
          * @tparam A
          *   The type of the successful value
          * @return
          *   A Success instance
          */
        def apply[A](value: A): Success[A] =
            value match
                case v: SuccessError[?]    => v.nest.asInstanceOf[Success[A]]
                case v: Fail[A] @unchecked => SuccessError(v)
                case v                     => v

        /** Extracts the value from a Success Result.
          *
          * @param self
          *   The Result to extract from
          * @tparam E
          *   The type of the error (not used in this case)
          * @tparam A
          *   The type of the successful value
          * @return
          *   A Maybe containing the successful value, or empty for non-Success Results
          */
        def unapply[E, A](self: Result[E, A]): Maybe.Ops[A] =
            self.fold(_ => Maybe.empty)(Maybe(_))

    end Success

    /** Represents an error in a Result. */
    sealed abstract class Error[+E]:
        /** Gets the failure value.
          *
          * @return
          *   The error value or exception
          */
        def getFailure: E | Throwable
    end Error
    object Error:
        def unapply[E, A](self: Result[E, A]): Maybe.Ops[E | Throwable] =
            self match
                case error: Error[E] @unchecked => Maybe(error.getFailure)
                case _                          => Maybe.empty
    end Error

    /** Represents a failure in a Result. */
    case class Fail[+E](error: E) extends Error[E]:
        def getFailure = error

    object Fail:
        /** Extracts the error value from a Fail Result.
          *
          * @param result
          *   The Result to extract from
          * @tparam E
          *   The type of the error
          * @tparam A
          *   The type of the successful value (not used in this case)
          * @return
          *   A Maybe containing the error value, or empty for non-Fail Results
          */
        def unapply[E, A](result: Result[E, A]): Maybe.Ops[E] =
            result match
                case result: Fail[E] @unchecked =>
                    Maybe(result.error)
                case _ => Maybe.empty
    end Fail

    /** Represents a panic situation in a Result. */
    case class Panic(exception: Throwable) extends Error[Nothing]:
        def getFailure = exception

    object Panic:
        /** Creates a Panic instance.
          *
          * @param exception
          *   The exception causing the panic
          * @return
          *   A Panic instance, or throws the exception if it's fatal
          */
        def apply(exception: Throwable): Panic =
            if NonFatal(exception) then
                new Panic(exception)
            else
                throw exception
    end Panic

    extension [E](self: Error[E])
        /** Gets the exception from an Error.
          *
          * @param ev
          *   Evidence that E is a subtype of Throwable
          * @return
          *   The exception
          */
        def exception(
            using
            @implicitNotFound("Error must be a 'Throwable'")
            ev: E <:< Throwable
        ): Throwable =
            self match
                case self: Fail[E] => self.error
                case self: Panic   => self.exception

    /** Provides extension methods for Result type */
    extension [E, A](self: Result[E, A])

        /** Checks if the Result is a Success.
          *
          * @return
          *   true if the Result is a Success, false otherwise
          */
        def isSuccess: Boolean =
            self match
                case _: Error[?] => false
                case _           => true

        /** Checks if the Result is a Fail.
          *
          * @return
          *   true if the Result is a Fail, false otherwise
          */
        def isFail =
            self.isInstanceOf[Fail[?]]

        /** Checks if the Result is a Panic.
          *
          * @return
          *   true if the Result is a Panic, false otherwise
          */
        def isPanic: Boolean =
            self.isInstanceOf[Panic]

        /** Gets the successful value if present.
          *
          * @return
          *   A Maybe containing the successful value, or empty for non-Success Results
          */
        def value: Maybe[A] =
            self match
                case self: Error[?] => Maybe.empty
                case self           => Maybe(self.asInstanceOf[A])

        /** Gets the error value if present.
          *
          * @return
          *   A Maybe containing the error value, or empty for non-Fail Results
          */
        @targetName("maybeError")
        def failure: Maybe[E] =
            self match
                case self: Fail[E] @unchecked => Maybe(self.error)
                case _                        => Maybe.empty

        /** Gets the panic exception if present.
          *
          * @return
          *   A Maybe containing the panic exception, or empty for non-Panic Results
          */
        @targetName("maybePanic")
        def panic: Maybe[Throwable] =
            self match
                case self: Panic => Maybe(self.exception)
                case _           => Maybe.empty

        /** Folds the Result into a value.
          *
          * @param ifFailure
          *   Function to apply if the Result is a failure
          * @param ifSuccess
          *   Function to apply if the Result is a success
          * @tparam B
          *   The type of the result of folding
          * @return
          *   The result of applying the appropriate function
          */
        inline def fold[B](inline ifFailure: Error[E] => B)(inline ifSuccess: A => B): B =
            self match
                case self: Error[E] @unchecked => ifFailure(self)
                case _ =>
                    try ifSuccess(self.asInstanceOf[Result[Nothing, A]].get)
                    catch
                        case ex => ifFailure(Panic(ex))

        /** Gets the successful value.
          *
          * @param ev
          *   Evidence that E is Nothing
          * @return
          *   The successful value
          * @throws NoSuchElementException
          *   if the Result is a Fail
          * @throws Throwable
          *   if the Result is a Panic
          */
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

        /** Gets the successful value or throws the error.
          *
          * @param ev
          *   Evidence that E is a subtype of Throwable
          * @return
          *   The successful value
          * @throws E
          *   if the Result is a Fail
          * @throws Throwable
          *   if the Result is a Panic
          */
        def getOrThrow(
            using
            @implicitNotFound("Error must be a 'Throwable' to invoke 'getOrThrow'. Found: '${E}'")
            ev: E <:< Throwable
        ): A =
            fold(e => throw e.exception)(identity)

        /** Gets the successful value or returns a default value.
          *
          * @param default
          *   The default value to return if the Result is not a Success
          * @tparam B
          *   A supertype of A
          * @return
          *   The successful value or the default value
          */
        inline def getOrElse[B >: A](inline default: => B): B =
            fold(_ => default)(identity)

        /** Returns this Result if it's a Success, or an alternative Result if it's not.
          *
          * @param alternative
          *   The alternative Result to return if this Result is not a Success
          * @tparam E2
          *   The error type of the alternative Result
          * @tparam B
          *   A supertype of A
          * @return
          *   This Result if it's a Success, or the alternative Result
          */
        def orElse[E2, B >: A](alternative: => Result[E2, B]): Result[E | E2, B] =
            fold(_ => alternative)(Result.success)

        /** Applies a function to the successful value of this Result.
          *
          * @param f
          *   The function to apply
          * @tparam E2
          *   The error type of the resulting Result
          * @tparam B
          *   The type of the resulting successful value
          * @return
          *   A new Result after applying the function
          */
        inline def flatMap[E2, B](inline f: A => Result[E2, B]): Result[E | E2, B] =
            self match
                case self: Error[E] @unchecked => self
                case self =>
                    try f(self.asInstanceOf[Result[Nothing, A]].get)
                    catch
                        case ex => Panic(ex)

        /** Flattens a nested Result.
          *
          * @param ev
          *   Evidence that A is a Result
          * @tparam E2
          *   The error type of the inner Result
          * @tparam B
          *   The successful type of the inner Result
          * @return
          *   The flattened Result
          */
        def flatten[E2, B](using ev: A <:< Result[E2, B]): Result[E | E2, B] =
            flatMap(ev)

        /** Applies a function to the successful value of this Result.
          *
          * @param f
          *   The function to apply
          * @tparam B
          *   The type of the resulting successful value
          * @return
          *   A new Result after applying the function
          */
        inline def map[B](inline f: A => B): Result[E, B] =
            flatMap(v => Result.success(f(v)))

        /** Applies a function to the error value of this Result.
          *
          * @param f
          *   The function to apply
          * @tparam E2
          *   The type of the resulting error
          * @return
          *   A new Result after applying the function to the error
          */
        inline def mapFail[E2](inline f: E => E2): Result[E2, A] =
            self match
                case Fail(e) =>
                    try Fail(f(e))
                    catch
                        case ex => Panic(ex)
                case _ => self.asInstanceOf[Result[E2, A]]

        /** Applies a predicate to the successful value of this Result.
          *
          * @param p
          *   The predicate to apply
          * @return
          *   A new Result that fails if the predicate doesn't hold
          */
        inline def withFilter(inline p: A => Boolean): Result[E | NoSuchElementException, A] =
            filter(p)

        /** Applies a predicate to the successful value of this Result.
          *
          * @param p
          *   The predicate to apply
          * @return
          *   A new Result that fails if the predicate doesn't hold
          */
        inline def filter(inline p: A => Boolean): Result[E | NoSuchElementException, A] =
            flatMap { v =>
                if !p(v) then
                    Fail(new NoSuchElementException("Predicate does not hold for " + v))
                else
                    Result(v)
            }

        /** Recovers from an error by applying a partial function.
          *
          * @param pf
          *   The partial function to apply to the error
          * @tparam B
          *   A supertype of A
          * @return
          *   A new Result with the error potentially recovered
          */
        inline def recover[B >: A](pf: PartialFunction[Error[E], B]): Result[E, B] =
            try
                self match
                    case self: Error[E] @unchecked if pf.isDefinedAt(self) =>
                        Result.success(pf(self))
                    case _ => self
            catch
                case ex => Panic(ex)

        /** Recovers from an error by applying a partial function that returns a new Result.
          *
          * @param pf
          *   The partial function to apply to the error
          * @tparam E2
          *   The error type of the resulting Result
          * @tparam B
          *   A supertype of A
          * @return
          *   A new Result with the error potentially recovered
          */
        inline def recoverWith[E2, B >: A](pf: PartialFunction[Error[E], Result[E2, B]]): Result[E | E2, B] =
            try
                self match
                    case self: Error[E] @unchecked if pf.isDefinedAt(self) =>
                        pf(self)
                    case _ => self
            catch
                case ex => Panic(ex)

        /** Converts the Result to an Either.
          *
          * @return
          *   An Either with Left containing the error or exception, and Right containing the successful value
          */
        def toEither: Either[E | Throwable, A] =
            fold(e => Left(e.getFailure))(Right(_))

        /** Converts the Result to a Maybe.
          *
          * @return
          *   A Maybe containing the successful value, or empty for non-Success Results
          */
        def toMaybe: Maybe[A] =
            fold(_ => Maybe.empty)(Maybe(_))

        /** Converts the Result to a Try.
          *
          * @param ev
          *   Evidence that E is a subtype of Throwable
          * @return
          *   A Try containing the successful value, or Failure with the error
          */
        def toTry(using
            @implicitNotFound("Fail type must be a 'Throwable' to invoke 'toTry'. Found: '${E}'")
            ev: E <:< Throwable
        ): Try[A] =
            fold(e => scala.util.Failure(e.getFailure.asInstanceOf[Throwable]))(scala.util.Success(_))

        /** Converts the Result to a Result[E, Unit].
          *
          * @return
          *   A new Result with the same error type E and Unit as the success type
          */
        def unit: Result[E, Unit] =
            map(_ => ())

        /** Swaps the success and failure cases of the Result.
          *
          * @return
          *   A new Result with success and failure swapped
          */
        def swap: Result[A, E] =
            self match
                case Fail(e)    => Result.success(e)
                case Success(v) => Result.fail(v)
                case _          => self.asInstanceOf[Result[A, E]]

        /** Checks if the Result is a Success and contains the given value.
          *
          * @param value
          *   The value to check for
          * @return
          *   true if the Result is a Success and contains the given value, false otherwise
          */
        def contains(value: A)(using CanEqual[A, A]): Boolean =
            self match
                case Success(`value`) => true
                case _                => false

        /** Checks if the Result is a Success and the predicate holds for its value.
          *
          * @param pred
          *   The predicate function to apply to the successful value
          * @return
          *   true if the Result is a Success and the predicate holds, false otherwise
          */
        def exists(pred: A => Boolean): Boolean =
            fold(_ => false)(pred)

        /** Checks if the Result is a Success and the predicate holds for its value, or if the Result is a Failure.
          *
          * @param pred
          *   The predicate function to apply to the successful value
          * @return
          *   true if the Result is a Failure, or if it's a Success and the predicate holds
          */
        def forall(pred: A => Boolean): Boolean =
            fold(_ => true)(pred)

        /** Returns a string representation of the Result.
          *
          * @return
          *   A string describing the Result's state and value
          */
        def show: String =
            self match
                case Panic(ex) => s"Panic($ex)"
                case Fail(ex)  => s"Fail($ex)"
                case v         => s"Success($v)"

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

            override def toString: String =
                "Success(" * depth + failure.toString + ")" * depth
        end SuccessError
    end internal
end Result
