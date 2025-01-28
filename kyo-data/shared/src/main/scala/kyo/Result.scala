package kyo

import Result.*
import scala.annotation.implicitNotFound
import scala.annotation.targetName
import scala.util.Try
import scala.util.control.NonFatal

/** A type representing computations that can succeed, fail with an expected error, or fail with an unexpected exception. Result combines
  * the features of `Either` (for expected errors) and `Try` (for unexpected exceptions) into a single type that can handle both cases while
  * maintaining type safety.
  *
  * Result has the following possible states:
  *   - `Success[A]`: Contains a successful value of type `A`
  *   - `Error[E]`: Base type for both Failure and Panic
  *     - `Failure[E]`: Represents expected errors of type `E`
  *     - `Panic`: Represents unexpected runtime exceptions
  *
  * Result provides several groups of operations:
  *   - Fold operations (`fold`, `foldError`, `foldOrThrow`) for matching on the different states
  *   - Map operations (`map`, `mapError`, `mapFailure`, `mapPanic`) for transforming specific states
  *   - FlatMap operations (`flatMap`, `flatMapError`, `flatMapFailure`, `flatMapPanic`) for sequencing computations and handling errors
  *   - Query operations (`isSuccess`, `isError`, `isFailure`, `isPanic`, `contains`, `exists`, `forall`)
  *   - Conversion operations (`toEither`, `toMaybe`, `toTry`)
  *
  * The implementation supports handling specific error types through `SafeClassTag`, allowing precise error handling for union types. This
  * enables handling specific failures while maintaining other error types in the result:
  *
  * {{{
  * result.flatMapError[NetworkError] { e =>  // Handles only NetworkError
  *   Result.succeed(fallbackValue)
  * } // Other error types remain unchanged
  * }}}
  *
  * Result uses an unboxed representation through Scala 3's opaque types, allowing the successful value to be stored directly without
  * additional allocation. This optimization is particularly important for high-performance code paths where the success case is the most
  * common outcome. Additionally, methods are marked as `inline` where function dispatch would introduce overhead, ensuring that operations
  * like `map` and `flatMap` are optimized at compile-time and avoid allocations.
  *
  * @tparam E
  *   The type of expected errors that can occur
  * @tparam A
  *   The type of the successful value
  */
opaque type Result[+E, +A] >: Success[A] | Error[E] = Success[A] | Error[E]

object Result:

    import internal.*

    /** Creates a Result from an expression that might throw an exception.
      *
      * @param expr
      *   The expression to evaluate
      * @return
      *   A Result containing either the successful value or a Panic with the caught exception
      */
    inline def apply[A](inline expr: => A): Result[Nothing, A] =
        try
            Success(expr)
        catch
            case ex =>
                Panic(ex)

    /** Creates a successful Result.
      *
      * @param value
      *   The successful value
      * @return
      *   A successful Result
      */
    def succeed[E, A](value: A): Result[E, A] = Success(value)

    /** Creates a failed Result.
      *
      * @param error
      *   The error value
      * @return
      *   A failed Result
      */
    def fail[E, A](error: E): Result[E, A] = Failure(error)

    /** Creates a Result representing a panic situation.
      *
      * @param exception
      *   The exception causing the panic
      * @return
      *   A Result in a panic state
      */
    def panic[E, A](exception: Throwable): Result[E, A] = Panic(exception)

    private val _unit   = Success(())
    private val _absent = Failure(Absent)

    /** Returns a successful Result containing unit.
      *
      * @return
      *   A successful Result containing unit
      */
    def unit[E]: Result[E, Unit] = _unit

    /** Returns a failed Result with an Absent failure.
      *
      * @return
      *   A failed Result with an Absent failure
      */
    def absent[A]: Result[Absent, A] = _absent

    /** Converts an Either to a Result.
      *
      * @param either
      *   The Either to convert
      * @return
      *   A Result equivalent to the input Either
      */
    def fromEither[E, A](either: Either[E, A]): Result[E, A] =
        either.fold(fail, succeed)

    /** Converts a Try to a Result.
      *
      * @param t
      *   The Try to convert
      * @return
      *   A Result equivalent to the input Try
      */
    def fromTry[A](t: Try[A]): Result[Throwable, A] = t.fold(fail, succeed)

    /** Collects a sequence of Results into a single Result containing a sequence of successful values.
      *
      * @param seq
      *   The sequence of Results to collect
      * @return
      *   A Result containing either a sequence of successful values or the first encountered error
      */
    def collect[E, A](seq: Seq[Result[E, A]]): Result[E, Seq[A]] =
        def loop(remaining: Seq[Result[E, A]], acc: Chunk[A]): Result[E, Chunk[A]] =
            remaining match
                case (head: Result[E, A]) +: tail =>
                    head.flatMap(value => loop(tail, acc.append(value)))
                case Seq() => Success(acc)

        try loop(seq, Chunk.empty[A])
        catch
            case ex => Panic(ex)
    end collect

    /** Evaluates an expression, catching a specific exception type.
      *
      * @param expr
      *   The expression to evaluate
      * @return
      *   A Result containing either the successful value, a Failure with the caught exception, or a Panic for other exceptions
      */
    inline def catching[E <: Throwable](
        using inline ct: SafeClassTag[E]
    )[A](inline expr: => A): Result[E, A] =
        try
            Success(expr)
        catch
            case ct(ex) => Failure(ex)
            case ex     => Panic(ex)

    /** Represents a successful Result. */
    opaque type Success[+A] = A | SuccessError[A]

    object Success:

        /** Creates a Success instance.
          *
          * @param value
          *   The successful value
          * @return
          *   A Success instance
          */
        def apply[A](value: A): Success[A] =
            value match
                case v: SuccessError[?]       => v.nest.asInstanceOf[Success[A]]
                case v: Failure[A] @unchecked => SuccessError(v)
                case v                        => v

        /** Extracts the value from a Success Result.
          *
          * @param self
          *   The Result to extract from
          * @return
          *   A Maybe containing the successful value, or empty for non-Success Results
          */
        def unapply[E, A](self: Result[E, A]): Maybe.Ops[A] =
            self.foldError(Present(_), _ => Absent)

    end Success

    /** Represents an error in a Result. */
    sealed abstract class Error[+E]:

        /** Gets the error value or panic exception.
          *
          * @return
          *   For Failure, returns the error value. For Panic, returns the exception.
          */
        def failureOrPanic: E | Throwable
    end Error

    object Error:

        def unapply[E, A](self: Result[E, A]): Maybe.Ops[E | Throwable] =
            self.failureOrPanic

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
                    case self: Failure[E] => self.failure
                    case self: Panic      => self.exception
        end extension
    end Error

    /** Represents a failure in a Result. */
    final case class Failure[+E](failure: E) extends Error[E]:
        def failureOrPanic = failure

    object Failure:

        /** Extracts the error value from a Failure Result.
          *
          * @param result
          *   The Result to extract from
          * @tparam E
          *   The type of the error
          * @tparam A
          *   The type of the successful value (not used in this case)
          * @return
          *   A Maybe containing the error value, or empty for non-Failure Results
          */
        def unapply[E, A](result: Result[E, A]): Maybe.Ops[E] =
            result match
                case result: Failure[E] @unchecked =>
                    Maybe(result.failure)
                case _ => Absent
    end Failure

    /** Represents an unexpected exception in a Result. */
    final case class Panic(exception: Throwable) extends Error[Nothing]:
        def failureOrPanic = exception

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

    /** Provides extension methods for Result type */
    extension [A](self: Success[A])
        def successValue: A = self match
            case v: SuccessError[?] => v.unnest.asInstanceOf[A]
            case v: A @unchecked    => v
    end extension

    /** Provides extension methods for Result type */
    extension [E, A](self: Result[E, A])

        /** Checks if the Result is a Success.
          *
          * @return
          *   true if the Result is a Success, false otherwise
          */
        def isSuccess: Boolean = !isError

        /** Checks if the Result is an Error (either Failure or Panic).
          *
          * @return
          *   true if the Result is an Error, false otherwise
          */
        def isError: Boolean = self.isInstanceOf[Error[?]]

        /** Checks if the Result is a Failure.
          *
          * @return
          *   true if the Result is a Failure, false otherwise
          */
        def isFailure: Boolean = self.isInstanceOf[Failure[?]]

        /** Checks if the Result is a Panic.
          *
          * @return
          *   true if the Result is a Panic, false otherwise
          */
        def isPanic: Boolean = self.isInstanceOf[Panic]

        /** Gets the successful value if present.
          *
          * @return
          *   A Maybe containing the successful value, or empty for non-Success Results
          */
        def value: Maybe[A] = foldError(Present(_), _ => Absent)

        /** Gets the error value or panic exception if present.
          *
          * @return
          *   A Maybe containing either the error value or panic exception, or empty for Success Results
          */
        def error: Maybe[Error[E]] = foldError(_ => Absent, Present(_))

        /** Gets the error value if present.
          *
          * @return
          *   A Maybe containing the error value, or empty for non-Failure Results
          */
        def failure: Maybe[E] = fold(_ => Absent, Present(_), _ => Absent)

        /** Gets the panic exception if present.
          *
          * @return
          *   A Maybe containing the panic exception, or empty for non-Panic Results
          */
        def panic: Maybe[Throwable] = fold(_ => Absent, _ => Absent, Present(_))

        /** Gets either the failure value or panic exception if present.
          *
          * @return
          *   A Maybe containing either the failure value or panic exception, or empty for Success Results
          */
        def failureOrPanic: Maybe[E | Throwable] = fold(_ => Absent, Present(_), Present(_))

        /** Folds a Result into a value, with separate handling for errors and successes.
          *
          * @param ifError
          *   Function to apply if the Result is an Error (either Failure or Panic)
          * @param ifSuccess
          *   Function to apply if the Result is a Success
          * @return
          *   The result of applying the appropriate function
          */
        inline def foldError[B](inline ifSuccess: A => B, ifError: Error[E] => B): B =
            try
                self match
                    case error: Error[E] @unchecked => ifError(error)
                    case _                          => ifSuccess(self.asInstanceOf[Result[Nothing, A]].getOrThrow)
            catch
                case ex => ifError(Panic(ex))

        /** Folds a Result into a value, with separate handling for successes, failures, and panics.
          *
          * @param ifSuccess
          *   Function to apply if the Result is a Success
          * @param ifFailure
          *   Function to apply if the Result is a Failure
          * @return
          *   The result of applying the appropriate function
          * @throws Throwable
          *   if the Result is a Panic
          */
        inline def fold[B](inline ifSuccess: A => B, inline ifFailure: E => B, inline ifPanic: Throwable => B): B =
            try
                self match
                    case Panic(ex)  => ifPanic(ex)
                    case Failure(e) => ifFailure(e)
                    case _          => ifSuccess(self.asInstanceOf[Result[Nothing, A]].getOrThrow)
            catch
                case ex if NonFatal(ex) => ifPanic(ex)

        /** Folds a Result into a value, with separate handling for successes and failures, throwing any panics.
          *
          * @param ifSuccess
          *   Function to apply if the Result is a Success
          * @param ifFailure
          *   Function to apply if the Result is a Failure
          * @return
          *   The result of applying the appropriate function
          * @throws Throwable
          *   if the Result is a Panic
          */
        inline def foldOrThrow[B](inline ifSuccess: A => B, inline ifFailure: E => B): B =
            fold(ifSuccess, ifFailure, throw _)

        /** Gets the successful value or throws the error.
          *
          * @param ev
          *   Evidence that E is a subtype of Throwable
          * @return
          *   The successful value
          * @throws E
          *   if the Result is a Failure
          * @throws Throwable
          *   if the Result is a Panic
          */
        def getOrThrow(
            using
            @implicitNotFound("Failure must be a 'Throwable' or 'Nothing' to invoke 'getOrThrow'. Found: '${E}'")
            ev: E <:< Throwable
        ): A =
            self match
                case self: Error[E] @unchecked => throw self.failureOrPanic.asInstanceOf[Throwable]
                case self: SuccessError[?]     => self.unnest.asInstanceOf[A]
                case self                      => self.asInstanceOf[A]
            end match
        end getOrThrow

        /** Gets the successful value or returns a default value.
          *
          * @param default
          *   The default value to return if the Result is not a Success
          * @return
          *   The successful value or the default value
          */
        inline def getOrElse[B >: A](inline default: => B): B =
            foldError(identity, _ => default)

        /** Returns this Result if it's a Success, or an alternative Result if it's not.
          *
          * @param alternative
          *   The alternative Result to return if this Result is not a Success
          * @return
          *   This Result if it's a Success, or the alternative Result
          */
        def orElse[E2, B >: A](alternative: => Result[E2, B]): Result[E | E2, B] =
            foldError(Result.succeed, _ => alternative)

        /** Applies a function to the successful value of this Result.
          *
          * @param f
          *   The function to apply
          * @return
          *   A new Result after applying the function
          */
        inline def map[B](inline f: A => B): Result[E, B] =
            flatMap(v => Result.succeed(f(v)))

        /** Maps the error value or panic exception to a new error type.
          *
          * @param f
          *   The function to apply to the error or panic
          * @return
          *   A new Result with the mapped error type
          */
        inline def mapError[E2](inline f: Error[E] => E2): Result[E2, A] =
            try foldError(Result.succeed, e => Result.fail(f(e)))
            catch
                case ex => Panic(ex)

        /** Maps only the failure value to a new error type, preserving panics.
          *
          * @param f
          *   The function to apply to the failure
          * @return
          *   A new Result with the mapped failure type
          */
        inline def mapFailure[E2](inline f: E => E2): Result[E2, A] =
            foldError(
                Result.succeed,
                {
                    case error: Failure[E] => Result.fail(f(error.failure))
                    case error: Panic      => error
                }
            )

        /** Maps only the panic exception to an error value.
          *
          * @param f
          *   The function to apply to the panic exception
          * @return
          *   A new Result with the mapped panic as a failure
          */
        inline def mapPanic[E2](inline f: Throwable => E2): Result[E | E2, A] =
            try fold(Result.succeed, Result.fail, e => Result.fail(f(e)))
            catch
                case ex => Panic(ex)

        /** Applies a function to the successful value of this Result.
          *
          * @param f
          *   The function to apply
          * @return
          *   A new Result after applying the function
          */
        inline def flatMap[E2, B](inline f: A => Result[E2, B]): Result[E | E2, B] =
            self match
                case self: Error[E] @unchecked => self
                case self =>
                    try f(self.asInstanceOf[Success[A]].getOrThrow)
                    catch
                        case ex =>
                            Panic(ex)

        /** Handles specific error types with a recovery function.
          *
          * @param f
          *   The function to apply to matching errors
          * @tparam E2
          *   The specific error type to handle
          * @return
          *   A new Result after applying the recovery function
          */
        inline def flatMapError[E2 <: E](
            using ct: SafeClassTag[E2]
        )[B >: A, E3 <: E, E4](f: (E2 | Throwable) => Result[E4, B])(using E <:< (E2 | E3)): Result[E3 | E4, B] =
            try
                self match
                    case Failure(ct(e)) => f(e)
                    case Panic(ex)      => f(ex)
                    case _              => self.asInstanceOf[Result[E3 | E4, B]]
            catch
                case ex => Panic(ex)

        /** Handles specific failure types with a recovery function.
          *
          * @param f
          *   The function to apply to matching failures
          * @tparam E2
          *   The specific error type to handle
          * @return
          *   A new Result after applying the recovery function
          */
        inline def flatMapFailure[E2 <: E](
            using ct: SafeClassTag[E2]
        )[B >: A, E3 <: E, E4](inline f: E2 => Result[E4, B])(using E <:< (E2 | E3)): Result[E3 | E4, B] =
            try
                self match
                    case Failure(ct(e)) => f(e)
                    case _              => self.asInstanceOf[Result[E3 | E4, B]]
            catch
                case ex => Panic(ex)

        /** Handles panic exceptions with a recovery function.
          *
          * @param f
          *   The function to apply to panic exceptions
          * @return
          *   A new Result after applying the recovery function
          */
        inline def flatMapPanic[B >: A, E2](inline f: Throwable => Result[E2, B]): Result[E | E2, B] =
            try
                self match
                    case Panic(ex) => f(ex)
                    case _         => self.asInstanceOf[Result[E | E2, B]]
            catch
                case ex => Panic(ex)

        /** Flattens a nested Result.
          *
          * @param ev
          *   Evidence that A is a Result
          * @return
          *   The flattened Result
          */
        def flatten[E2, B](using ev: A <:< Result[E2, B]): Result[E | E2, B] =
            flatMap(ev)

        /** Converts the Result to an Either.
          *
          * @return
          *   An Either with Left containing the error or exception, and Right containing the successful value
          */
        def toEither: Either[E | Throwable, A] =
            fold(Right(_), Left(_), Left(_))

        /** Converts the Result to a Maybe.
          *
          * @return
          *   A Maybe containing the successful value, or empty for non-Success Results
          */
        def toMaybe: Maybe[A] =
            foldError(Present(_), _ => Absent)

        /** Converts the Result to a Try.
          *
          * @param ev
          *   Evidence that E is a subtype of Throwable
          * @return
          *   A Try containing the successful value, or Failure with the error
          */
        def toTry(using
            @implicitNotFound("Failure type must be a 'Throwable' to invoke 'toTry'. Found: '${E}'")
            ev: E <:< Throwable
        ): Try[A] =
            foldError(scala.util.Success(_), e => scala.util.Failure(e.failureOrPanic.asInstanceOf[Throwable]))

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
            fold(Result.fail, Result.succeed, _ => self.asInstanceOf[Result[A, E]])

        /** Checks if the Result is a Success and contains the given value.
          *
          * @param value
          *   The value to check for
          * @return
          *   true if the Result is a Success and contains the given value, false otherwise
          */
        def contains(value: A)(using CanEqual[A, A]): Boolean =
            foldError(_ == value, _ => false)

        /** Checks if the Result is a Success and the predicate holds for its value.
          *
          * @param pred
          *   The predicate function to apply to the successful value
          * @return
          *   true if the Result is a Success and the predicate holds, false otherwise
          */
        def exists(pred: A => Boolean): Boolean =
            foldError(pred, _ => false)

        /** Checks if the Result is a Success and the predicate holds for its value, or if the Result is a Failure.
          *
          * @param pred
          *   The predicate function to apply to the successful value
          * @return
          *   true if the Result is a Failure, or if it's a Success and the predicate holds
          */
        def forall(pred: A => Boolean): Boolean =
            foldError(pred, _ => true)

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
                    Result.fail(new NoSuchElementException("Predicate does not hold for " + v))
                else
                    Result.succeed(v)
            }

        /** Returns a string representation of the Result.
          *
          * @return
          *   A string describing the Result's state and value
          */
        def show(using r: Render[Result[E, A]]): String = r.asString(self)

    end extension

    private object internal:

        /** Internal representation that introduces boxing to distinguish Success(Error) from Error.
          *
          * Due to Result's unboxed representation of Success, there's no way to distinguish between a direct Error value and a Success
          * containing an Error. This class introduces the necessary boxing to disambiguate these cases.
          *
          * This class is used in methods like flatten, map, and flatMap where we need to preserve the Success wrapper around Error values.
          * The depth parameter allows handling multiple levels of nesting, which is necessary when working with deeply nested Result types.
          *
          * @param failure
          *   The Error value that needs boxing
          * @param depth
          *   Number of Success wrappers (for handling multiple levels of nesting)
          */
        case class SuccessError[+A](error: Error[A], depth: Int = 1):
            def unnest: Result[Any, A] =
                if depth > 1 then
                    SuccessError(error, depth - 1)
                else
                    error
            def nest: Success[A] =
                SuccessError(error, depth + 1)

            override def toString: String =
                "Success(" * depth + error.toString + ")" * depth
        end SuccessError
    end internal

    inline given [E, A](using inline ce: CanEqual[A, A]): CanEqual[Result[E, A], Result[E, A]] = CanEqual.derived
    inline given [E, A: Flat]: Flat[Result[E, A]]                                              = Flat.unsafe.bypass
    inline given [E, A]: CanEqual[Result[E, A], Panic]                                         = CanEqual.derived

    given [E, A, ResultEA <: Result[E, A]](using re: Render[E], ra: Render[A]): Render[ResultEA] with
        def asText(value: ResultEA): String = value match
            case Success(a)    => s"Success(${ra.asText(a.asInstanceOf[A])})"
            case f: Failure[?] => s"Failure(${re.asText(f.failure.asInstanceOf[E])})"
            case other         => other.toString()
    end given

    /** A subtype of Result representing computations that can succeed or fail with an expected error. Result is effectively the Kyo
      * equivalent of Either.
      *
      * Result has the following possible states:
      *   - `Success[A]`: Contains a successful value of type `A`
      *   - `Failure[E]`: Represents expected errors of type `E`
      *
      * Being a subtype of Result, Result.Partial supports all the operations of Result as a few narrower versions of these methods:
      *   - foldPartial
      *   - toEitherPartial
      *   - flattenPartial
      *
      * @tparam E
      *   The type of expected errors that can occur
      * @tparam A
      *   The type of the successful value
      */
    opaque type Partial[+E, +A] >: Success[A] | Failure[E] <: Result[E, A] = Success[A] | Failure[E]

    object Partial:
        inline given [E, A](using inline ce: CanEqual[A, A]): CanEqual[Partial[E, A], Partial[E, A]] = CanEqual.derived
        inline given [E, A](using inline ce: CanEqual[A, A]): CanEqual[Partial[E, A], Result[E, A]]  = CanEqual.derived
        inline given [E, A: Flat]: Flat[Partial[E, A]]                                               = Flat.unsafe.bypass

        extension [E, A](self: Partial[E, A])
            inline def foldPartial[B](inline ifSuccess: A => B, inline ifFailure: E => B): B =
                self match
                    case failure: Failure[E] @unchecked => ifFailure(failure.failure)
                    case other                          => ifSuccess(other.asInstanceOf[Success[A]].successValue)

            /** Converts the Partial to an Either.
              *
              * @return
              *   An Either with Left containing the error or exception, and Right containing the successful value
              */
            def toEitherPartial: Either[E, A] =
                foldPartial(Right(_), Left(_))

            /** Flattens a nested Partial.
              *
              * @param ev
              *   Evidence that A is a Partial
              * @return
              *   The flattened Partial
              */
            def flattenPartial[E2, B](using ev: A <:< Partial[E2, B]): Partial[E | E2, B] =
                foldPartial(a => ev(a), _ => self.asInstanceOf[Partial[E, B]])

        end extension

    end Partial

end Result
