package kyo

import Maybe.*
import Maybe.internal.*

/** Represents an optional value that can be either Present or Absent.
  *
  * @tparam A
  *   the type of the optional value
  */
opaque type Maybe[+A] >: (Absent | Present[A]) = Absent | Present[A]

export Maybe.Absent
export Maybe.Present

/** Companion object for Maybe type */
object Maybe:
    inline given [A, B](using inline ce: CanEqual[A, B]): CanEqual[Maybe[A], Maybe[B]] = CanEqual.derived
    given [A]: Conversion[Maybe[A], IterableOnce[A]]                                   = _.iterator

    /** Creates a Maybe instance from a value.
      *
      * @param v
      *   the value to wrap
      * @tparam A
      *   the type of the value
      * @return
      *   a Maybe instance containing the value, or Absent if the value is null
      */
    def apply[A](v: A): Maybe[A] =
        if isNull(v) then Absent
        else Present(v)

    /** Converts an Option to a Maybe.
      *
      * @param opt
      *   the Option to convert
      * @tparam A
      *   the type of the value
      * @return
      *   a Maybe instance equivalent to the input Option
      */
    def fromOption[A](opt: Option[A]): Maybe[A] =
        opt match
            case Some(v) => Present(v)
            case None    => Absent

    /** Creates an absent Maybe instance.
      *
      * @tparam A
      *   the type parameter of the Maybe
      * @return
      *   an Absent instance
      */
    def empty[A]: Maybe[A] = Absent

    /** Creates a Maybe instance based on a condition.
      *
      * @param cond
      *   the condition to evaluate
      * @param v
      *   the value to wrap if the condition is true
      * @tparam A
      *   the type of the value
      * @return
      *   a Maybe instance containing the value if the condition is true, or Absent otherwise
      */
    inline def when[A](cond: Boolean)(inline v: => A): Maybe[A] =
        if cond then v else Absent

    /** Represents a defined value in a Maybe. */
    opaque type Present[+A] = A | PresentEmpty

    object Present:

        /** Creates a Present instance.
          *
          * @param v
          *   the value to wrap
          * @tparam A
          *   the type of the value
          * @return
          *   a Present instance containing the value
          */
        def apply[A](v: A): Present[A] =
            v match
                case v: PresentEmpty => v.nest
                case v: Absent       => PresentEmpty.one
                case v               => v

        /** Extracts the value from a Maybe instance.
          *
          * @param opt
          *   the Maybe instance to extract from
          * @tparam A
          *   the type of the value
          * @return
          *   the extracted value wrapped in Maybe.Ops
          */
        def unapply[A](opt: Maybe[A]): Maybe.Ops[A] = opt

    end Present

    /** Provides operations on Maybe instances. */
    implicit final class Ops[A](maybe: Maybe[A]) extends AnyVal:
        /** Checks if the Maybe instance is empty.
          *
          * @return
          *   true if the instance is empty, false otherwise
          */
        def isEmpty: Boolean = maybe.isEmpty

        /** Gets the value contained in the Maybe instance.
          *
          * @return
          *   the contained value
          * @throws NoSuchElementException
          *   if the instance is empty
          */
        def get: A = maybe.get
    end Ops

    /** Represents an empty Maybe instance. */
    sealed abstract class Absent
    case object Absent extends Absent

    extension [A](self: Maybe[A])

        /** Converts the Maybe to an Option.
          *
          * @return
          *   an Option containing the value if defined, or None if empty
          */
        def toOption: Option[A] =
            if isEmpty then None
            else Some(get)

        /** Checks if the Maybe instance is empty.
          *
          * @return
          *   true if the instance is empty, false otherwise
          */
        def isEmpty: Boolean =
            self.isInstanceOf[Absent]

        /** Checks if the Maybe instance is defined.
          *
          * @return
          *   true if the instance is defined, false otherwise
          */
        inline def isDefined: Boolean = !isEmpty

        /** Checks if the Maybe instance is non-empty.
          *
          * @return
          *   true if the instance is non-empty, false otherwise
          */
        inline def nonEmpty: Boolean = !isEmpty

        /** Gets the value contained in the Maybe instance.
          *
          * @return
          *   the contained value
          * @throws NoSuchElementException
          *   if the instance is empty
          */
        def get: A =
            (self: @unchecked) match
                case _: Absent =>
                    throw new NoSuchElementException("Maybe.get")
                case self: PresentEmpty =>
                    self.unnest.asInstanceOf[A]
                case v: A =>
                    v

        /** Gets the value if defined, or returns a default value if empty.
          *
          * @param default
          *   the default value to return if empty
          * @tparam B
          *   a supertype of A
          * @return
          *   the contained value if defined, or the default value if empty
          */
        inline def getOrElse[B >: A](inline default: => B): B =
            if isEmpty then default else get

        /** Applies one of two functions depending on whether the Maybe is empty or defined.
          *
          * @param ifEmpty
          *   the function to apply if empty
          * @param ifDefined
          *   the function to apply if defined
          * @tparam B
          *   the return type of both functions
          * @return
          *   the result of applying the appropriate function
          */
        inline def fold[B](inline ifEmpty: => B)(inline ifDefined: A => B): B =
            if isEmpty then ifEmpty else ifDefined(get)

        /** Applies a function to the contained value if defined.
          *
          * @param f
          *   the function to apply
          * @tparam B
          *   the return type of the function
          * @return
          *   a new Maybe containing the result of the function if defined, or Absent if empty
          */
        inline def map[B](inline f: A => B): Maybe[B] =
            if isEmpty then Absent else f(get)

        /** Applies a function that returns a Maybe to the contained value if defined.
          *
          * @param f
          *   the function to apply
          * @tparam B
          *   the type parameter of the resulting Maybe
          * @return
          *   the result of applying the function if defined, or Absent if empty
          */
        inline def flatMap[B](inline f: A => Maybe[B]): Maybe[B] =
            if isEmpty then Maybe.empty else f(get)

        /** Flattens a Maybe of Maybe into a single Maybe.
          *
          * @param ev
          *   evidence that A is a subtype of Maybe[B]
          * @tparam B
          *   the type parameter of the inner Maybe
          * @return
          *   the flattened Maybe
          */
        inline def flatten[B](using inline ev: A <:< Maybe[B]): Maybe[B] =
            if isEmpty then Absent else ev(get)

        /** Filters the Maybe based on a predicate.
          *
          * @param f
          *   the predicate function
          * @return
          *   the Maybe if it's defined and satisfies the predicate, or Absent otherwise
          */
        inline def withFilter(inline f: A => Boolean): Maybe[A] =
            filter(f)

        /** Filters the Maybe based on a predicate.
          *
          * @param f
          *   the predicate function
          * @return
          *   the Maybe if it's defined and satisfies the predicate, or Absent otherwise
          */
        inline def filter(inline f: A => Boolean): Maybe[A] =
            if isEmpty || f(get) then self else Absent

        /** Filters the Maybe based on a negated predicate.
          *
          * @param f
          *   the predicate function to negate
          * @return
          *   the Maybe if it's defined and doesn't satisfy the predicate, or Absent otherwise
          */
        inline def filterNot(inline f: A => Boolean): Maybe[A] =
            if isEmpty || !f(get) then self else Absent

        /** Checks if the Maybe contains a specific value.
          *
          * @param elem
          *   the value to check for
          * @param ev
          *   evidence of equality between A and B
          * @tparam B
          *   the type of the element to check
          * @return
          *   true if the Maybe is defined and contains the specified value, false otherwise
          */
        def contains[B](elem: B)(using CanEqual[A, B]): Boolean =
            !isEmpty && get == elem

        /** Checks if the Maybe satisfies a predicate.
          *
          * @param f
          *   the predicate function
          * @return
          *   true if the Maybe is defined and satisfies the predicate, false otherwise
          */
        inline def exists(inline f: A => Boolean): Boolean =
            !isEmpty && f(get)

        /** Checks if the Maybe satisfies a predicate or is empty.
          *
          * @param f
          *   the predicate function
          * @return
          *   true if the Maybe is empty or satisfies the predicate, false otherwise
          */
        inline def forall(inline f: A => Boolean): Boolean =
            isEmpty || f(get)

        /** Applies a side-effecting function to the contained value if defined.
          *
          * @param f
          *   the function to apply
          */
        inline def foreach(inline f: A => Unit): Unit =
            if !isEmpty then f(get)

        /** Applies a partial function to the contained value if defined.
          *
          * @param pf
          *   the partial function to apply
          * @tparam B
          *   the return type of the partial function
          * @return
          *   a new Maybe containing the result of the partial function if defined and applicable, or Absent otherwise
          */
        inline def collect[B](pf: PartialFunction[A, B]): Maybe[B] =
            if !isEmpty then
                val value = get
                if pf.isDefinedAt(value) then
                    pf(value)
                else
                    Absent
                end if
            else Absent

        /** Returns this Maybe if defined, or an alternative Maybe if empty.
          *
          * @param alternative
          *   the alternative Maybe to return if this is empty
          * @tparam B
          *   a supertype of A
          * @return
          *   this Maybe if defined, or the alternative if empty
          */
        inline def orElse[B >: A](inline alternative: => Maybe[B]): Maybe[B] =
            if isEmpty then alternative else self

        /** Combines this Maybe with another Maybe into a tuple.
          *
          * @param that
          *   the Maybe to combine with
          * @tparam B
          *   the type parameter of the other Maybe
          * @return
          *   a new Maybe containing a tuple of both values if both are defined, or Absent if either is empty
          */
        def zip[B](that: Maybe[B]): Maybe[(A, B)] =
            if isEmpty || that.isEmpty then Absent else (get, that.get)

        /** Creates an iterator over the contained value.
          *
          * @return
          *   an iterator with a single element if defined, or an empty iterator if empty
          */
        def iterator: Iterator[A] =
            if isEmpty then collection.Iterator.empty else collection.Iterator.single(get)

        /** Converts the Maybe to a List.
          *
          * @return
          *   a List containing the value if defined, or an empty List if empty
          */
        def toList: List[A] =
            if isEmpty then List.empty else get :: Nil

        /** Converts the Maybe to a Right-biased Either.
          *
          * @param left
          *   the value to use for the Left side if this Maybe is empty
          * @tparam X
          *   the type of the Left side
          * @return
          *   a Right containing the value if defined, or a Left containing the provided value if empty
          */
        inline def toRight[X](inline left: => X): Either[X, A] =
            if isEmpty then Left(left) else Right(get)

        /** Converts the Maybe to a Left-biased Either.
          *
          * @param right
          *   the value to use for the Right side if this Maybe is empty
          * @tparam X
          *   the type of the Right side
          * @return
          *   a Left containing the value if defined, or a Right containing the provided value if empty
          */
        inline def toLeft[X](inline right: => X): Either[A, X] =
            if isEmpty then Right(right) else Left(get)

        def show: String =
            if isEmpty then "Absent"
            else s"Present(${get})"

    end extension

    private[kyo] object internal:

        case class PresentEmpty(val depth: Int):
            def unnest =
                if depth > 1 then
                    PresentEmpty(depth - 1)
                else
                    Absent
            def nest =
                PresentEmpty(depth + 1)

            override def toString: String =
                "Present(" * depth + "Absent" + ")" * depth
        end PresentEmpty

        object PresentEmpty:
            val cache = (0 until 100).map(new PresentEmpty(_)).toArray
            val one   = PresentEmpty(1)
            def apply(depth: Int): PresentEmpty =
                if depth < cache.length then
                    cache(depth)
                else
                    new PresentEmpty(depth)
        end PresentEmpty
    end internal
end Maybe
