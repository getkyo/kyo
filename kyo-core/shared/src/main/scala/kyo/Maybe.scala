package kyo

import Maybe.*
import Maybe.internal.*

opaque type Maybe[+T] >: (Empty | Defined[T]) = Empty | Defined[T]

object Maybe:
    given [T, U](using CanEqual[T, U]): CanEqual[Maybe[T], Maybe[U]] = CanEqual.derived
    given [T]: Conversion[Maybe[T], IterableOnce[T]]                 = _.iterator
    given [T: Flat]: Flat[Maybe[T]]                                  = Flat.unsafe.bypass

    def apply[T](v: T): Maybe[T] =
        if isNull(v) then
            Empty
        else
            v match
                case v: DefinedEmpty => v.nest
                case v               => v

    def fromScala[T](opt: Option[T]): Maybe[T] =
        opt match
            case Some(v) => Defined(v)
            case None    => Empty

    def empty[T]: Maybe[T] = Empty

    def when[T](cond: Boolean)(v: => T): Maybe[T] =
        if cond then v else Empty

    opaque type Defined[+T] = T | DefinedEmpty

    object Defined:

        def apply[T](v: T): Defined[T] =
            v match
                case v: DefinedEmpty => v.nest
                case v: Empty        => DefinedEmpty()
                case v               => v

        // TODO avoid allocation
        def unapply[T](opt: Maybe[T]): Option[T] =
            opt.toScala
    end Defined

    sealed abstract class Empty
    case object Empty extends Empty

    extension [T](self: Maybe[T])

        inline def toScala: Option[T] =
            if isEmpty then None
            else Some(get)

        inline def isEmpty: Boolean =
            self match
                case _: Empty => true
                case _        => false

        inline def isDefined: Boolean = !isEmpty

        inline def nonEmpty: Boolean = !isEmpty

        // TODO compilation failure if inlined
        def get: T =
            (self: @unchecked) match
                case _: Empty =>
                    throw new NoSuchElementException("Maybe.get")
                case self: DefinedEmpty =>
                    self.unnest.asInstanceOf[T]
                case v: T @unchecked =>
                    v

        inline def getOrElse[B >: T](inline default: => B): B =
            if isEmpty then default else get

        inline def fold[U](inline ifEmpty: => U, inline ifDefined: T => U): U =
            if isEmpty then ifEmpty else ifDefined(get)

        inline def map[U](inline f: T => U): Maybe[U] =
            if isEmpty then Empty else f(get)

        inline def flatMap[U](inline f: T => Maybe[U]): Maybe[U] =
            if isEmpty then Maybe.empty else f(get)

        inline def flatten[U](using inline ev: T <:< Maybe[U]): Maybe[U] =
            if isEmpty then Empty else ev(get)

        inline def filter(inline f: T => Boolean): Maybe[T] =
            if isEmpty || f(get) then self else Empty

        inline def filterNot(inline f: T => Boolean): Maybe[T] =
            if isEmpty || !f(get) then self else Empty

        // TODO compilation failure if inlined
        def contains[U](elem: U)(using CanEqual[T, U]): Boolean =
            !isEmpty && self.get == elem

        inline def exists(inline f: T => Boolean): Boolean =
            !isEmpty && f(get)

        inline def forall(inline f: T => Boolean): Boolean =
            isEmpty || f(get)

        inline def foreach(inline f: T => Unit): Unit =
            if !isEmpty then f(get)

        inline def collect[U](inline pf: PartialFunction[T, U]): Maybe[U] =
            if !isEmpty then
                val value = get
                if pf.isDefinedAt(value) then
                    pf(value)
                else
                    Empty
                end if
            else Empty

        inline def orElse[B >: T](inline alternative: => Maybe[B]): Maybe[B] =
            if isEmpty then alternative else self

        def zip[B](that: Maybe[B]): Maybe[(T, B)] =
            if isEmpty || that.isEmpty then Empty else (get, that.get)

        inline def iterator: Iterator[T] =
            if isEmpty then collection.Iterator.empty else collection.Iterator.single(get)

        inline def toList: List[T] =
            if isEmpty then List.empty else get :: Nil

        inline def toRight[X](inline left: => X): Either[X, T] =
            if isEmpty then Left(left) else Right(get)

        inline def toLeft[X](inline right: => X): Either[T, X] =
            if isEmpty then Right(right) else Left(get)

    end extension

    private[kyo] object internal:

        case class DefinedEmpty(val depth: Int):
            def unnest =
                if depth > 1 then
                    DefinedEmpty(depth - 1)
                else
                    Empty
            def nest =
                DefinedEmpty(depth + 1)
        end DefinedEmpty

        object DefinedEmpty:
            val cache = (0 until 100).map(new DefinedEmpty(_)).toArray
            def apply(depth: Int = 1): DefinedEmpty =
                if depth < cache.length then
                    cache(depth)
                else
                    new DefinedEmpty(depth)
        end DefinedEmpty
    end internal
end Maybe
