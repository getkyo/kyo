package kyo2

import Chunk.Indexed
import kernel.Frame
import kernel.Loop
import scala.annotation.tailrec
import scala.reflect.ClassTag

sealed abstract class Chunk[A] derives CanEqual:

    import Chunk.internal.*

    //////////////////
    // O(1) methods //
    //////////////////

    def size: Int

    final def isEmpty: Boolean = size == 0

    final def take(n: Int): Chunk[A] =
        dropLeftAndRight(0, size - Math.min(Math.max(0, n), size))

    final def dropLeft(n: Int): Chunk[A] =
        dropLeftAndRight(Math.min(size, Math.max(0, n)), 0)

    final def dropRight(n: Int): Chunk[A] =
        dropLeftAndRight(0, Math.min(size, Math.max(0, n)))

    final def slice(from: Int, until: Int): Chunk[A] =
        dropLeftAndRight(Math.max(0, from), size - Math.min(size, until))

    final def dropLeftAndRight(left: Int, right: Int): Chunk[A] =
        @tailrec def loop(c: Chunk[A], left: Int, right: Int): Chunk[A] =
            val size = c.size - left - right
            if size <= 0 then Chunk.empty
            else
                c match
                    case Drop(chunk, dropLeft, dropRight, _) =>
                        Drop(chunk, left + dropLeft, right + dropRight, size)
                    case Append(chunk, value, size) if right > 0 =>
                        loop(chunk, left, right - 1)
                    case _ =>
                        Drop(c, left, right, size)
            end if
        end loop
        loop(this, left, right)
    end dropLeftAndRight

    final def append(v: A): Chunk[A] =
        Append(this, v, size + 1)

    final def last: A =
        @tailrec def loop(c: Chunk[A], index: Int): A =
            c match
                case c if index >= c.size || index < 0 =>
                    throw new NoSuchElementException
                case c: Append[A] =>
                    if index == c.size - 1 then
                        c.value
                    else
                        loop(c.chunk, index)
                case c: Drop[A] =>
                    loop(c.chunk, index + c.dropLeft)
                case c: Indexed[A] =>
                    c(index)
        loop(this, this.size - 1)
    end last

    //////////////////
    // O(n) methods //
    //////////////////

    final def concat(other: Chunk[A]): Chunk[A] =
        if isEmpty then other
        else if other.isEmpty then this
        else
            val s     = size
            val array = new Array[A](s + other.size)(using ClassTag(classOf[Any]))
            this.copyTo(array, 0)
            other.copyTo(array, s)
            Compact(array)
        end if
    end concat

    final def map[B, S](f: A => B < S)(using Frame): Chunk[B] < S =
        if isEmpty then Chunk.empty
        else
            val size    = this.size
            val indexed = this.toIndexed
            Loop.indexed(Chunk.empty[B]) { (idx, acc) =>
                if idx < size then
                    f(indexed(idx)).map(u => Loop.continue(acc.append(u)))
                else
                    Loop.done(acc)
            }
    end map

    final def filter[S](f: A => Boolean < S)(using Frame): Chunk[A] < S =
        if isEmpty then Chunk.empty
        else
            val size    = this.size
            val indexed = this.toIndexed
            Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
                if idx < size then
                    val v = indexed(idx)
                    f(v).map {
                        case true  => Loop.continue(acc.append(v))
                        case false => Loop.continue(acc)
                    }
                else
                    Loop.done(acc)
            }
    end filter

    final def takeWhile[S](f: A => Boolean < S)(using Frame): Chunk[A] < S =
        if isEmpty then Chunk.empty
        else
            val size    = this.size
            val indexed = this.toIndexed
            Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
                if idx < size then
                    val v = indexed(idx)
                    f(v).map {
                        case true  => Loop.continue(acc.append(v))
                        case false => Loop.done(acc)
                    }
                else
                    Loop.done(acc)
            }

    final def dropWhile[S](f: A => Boolean < S)(using Frame): Chunk[A] < S =
        if isEmpty then Chunk.empty
        else
            val size    = this.size
            val indexed = this.toIndexed
            Loop.indexed(()) { (idx, _) =>
                if idx < size then
                    val v = indexed(idx)
                    f(v).map {
                        case true  => Loop.continue
                        case false => Loop.done(indexed.dropLeft(idx))
                    }
                else
                    Loop.done(Chunk.empty)
            }

    final def changes: Chunk[A] =
        changes(null)

    final def changes(first: A | Null): Chunk[A] =
        if isEmpty then Chunk.empty
        else
            val size    = this.size
            val indexed = this.toIndexed
            @tailrec def loop(idx: Int, prev: A | Null, acc: Chunk[A]): Chunk[A] =
                if idx < size then
                    val v = indexed(idx)
                    if v.equals(prev) then
                        loop(idx + 1, prev, acc)
                    else
                        loop(idx + 1, v, acc.append(v))
                    end if
                else
                    acc
            loop(0, first, Chunk.empty)
    end changes

    final def collect[B, S](pf: PartialFunction[A, B < S])(using Frame): Chunk[B] < S =
        if isEmpty then Chunk.empty
        else
            val size    = this.size
            val indexed = this.toIndexed
            Loop.indexed(Chunk.empty[B]) { (idx, acc) =>
                if idx < size then
                    val v = indexed(idx)
                    if pf.isDefinedAt(v) then
                        pf(v).map { u =>
                            Loop.continue(acc.append(u))
                        }
                    else
                        Loop.continue(acc)
                    end if
                else
                    Loop.done(acc)
            }

    final def collectUnit[B, S](pf: PartialFunction[A, Unit < S])(using Frame): Unit < S =
        if isEmpty then ()
        else
            val size    = this.size
            val indexed = this.toIndexed
            Loop.indexed(()) { (idx, _) =>
                if idx < size then
                    val v = indexed(idx)
                    if pf.isDefinedAt(v) then
                        pf(v).andThen {
                            Loop.continue
                        }
                    else
                        Loop.continue
                    end if
                else
                    Loop.done
            }

    final def foreach[S](f: A => Unit < S)(using Frame): Unit < S =
        if isEmpty then ()
        else
            val size    = this.size
            val indexed = this.toIndexed
            Loop.indexed(()) { (idx, _) =>
                if idx < size then
                    val v = indexed(idx)
                    f(v).andThen(Loop.continue)
                else
                    Loop.done
            }
    end foreach

    final def foldLeft[S, U](init: U)(f: (U, A) => U < S)(using Frame): U < S =
        if isEmpty then init
        else
            val size    = this.size
            val indexed = this.toIndexed
            Loop.indexed(init) { (idx, acc) =>
                if idx < size then
                    f(acc, indexed(idx)).map(nacc => Loop.continue(nacc))
                else
                    Loop.done(acc)
            }
    end foldLeft

    final def toSeq: IndexedSeq[A] =
        if isEmpty then IndexedSeq.empty
        else
            this match
                case c: FromSeq[A] => c.seq
                case _             => toArrayInternal.toIndexedSeq

    final def toIndexed: Indexed[A] =
        if isEmpty then cachedEmpty.asInstanceOf[Indexed[A]]
        else
            this match
                case c: Indexed[A] => c
                case _             => Compact(toArrayInternal)

    final def flatten[B](using ev: A =:= Chunk[B]): Chunk[B] =
        if isEmpty then Chunk.empty
        else
            val nested = this.toArrayInternal

            @tailrec def totalSize(idx: Int = 0, acc: Int = 0): Int =
                if idx < nested.length then
                    val chunk = nested(idx).asInstanceOf[Chunk[B]]
                    totalSize(idx + 1, acc + chunk.size)
                else
                    acc

            val unnested = new Array[B](totalSize())(using ClassTag(classOf[Any]))

            @tailrec def copy(idx: Int = 0, offset: Int = 0): Unit =
                if idx < nested.length then
                    val chunk = nested(idx).asInstanceOf[Chunk[B]]
                    chunk.copyTo(unnested, offset)
                    copy(idx + 1, offset + chunk.size)
            copy()

            Compact(unnested)
    end flatten

    final def copyTo(array: Array[A], start: Int): Unit =
        copyTo(array, start, size)

    final def copyTo(array: Array[A], start: Int, elements: Int): Unit =
        @tailrec def loop(c: Chunk[A], end: Int, dropLeft: Int, dropRight: Int): Unit =
            c match
                case c: Append[A] =>
                    if dropRight > 0 then
                        loop(c.chunk, end, dropLeft, dropRight - 1)
                    else if end > 0 then
                        array(start + end - 1) = c.value
                        loop(c.chunk, end - 1, dropLeft, dropRight)
                case c: Drop[A] =>
                    loop(c.chunk, end, dropLeft + c.dropLeft, dropRight + c.dropRight)
                case c: Tail[A] =>
                    loop(c.chunk, end, dropLeft + c.offset, dropRight)
                case c: Compact[A] =>
                    val l = c.array.length
                    if l > 0 then
                        System.arraycopy(c.array, dropLeft, array, start, l - dropRight - dropLeft)
                case c: FromSeq[A] =>
                    val seq  = c.seq
                    val size = Math.min(end, c.seq.size - dropLeft - dropRight)
                    @tailrec def loop(index: Int): Unit =
                        if index < size then
                            array(start + index) = seq(index + dropLeft)
                            loop(index + 1)
                    loop(0)
        if !isEmpty then
            loop(this, elements, 0, 0)
    end copyTo

    final def toArray(using ClassTag[A]): Array[A] =
        val array = new Array[A](size)
        copyTo(array, 0)
        array
    end toArray

    final private def toArrayInternal: Array[A] =
        this match
            case c if c.isEmpty =>
                cachedEmpty.array.asInstanceOf[Array[A]]
            case c: Compact[A] =>
                c.array
            case c =>
                c.toArray(using ClassTag(classOf[Any]))

    override def equals(other: Any) =
        (this eq other.asInstanceOf[Object]) || {
            other match
                case other: Chunk[?] =>
                    (this.isEmpty && other.isEmpty) ||
                    (this.size == other.size && {
                        val s = this.size
                        val a = this.toIndexed
                        val b = other.toIndexed
                        @tailrec def loop(idx: Int = 0): Boolean =
                            if idx < s then
                                inline given CanEqual[Any, Any] = CanEqual.derived
                                a(idx) == b(idx) && loop(idx + 1)
                            else
                                true
                        loop()
                    })
                case _ =>
                    false
        }

    override def hashCode(): Int =
        if isEmpty then
            1
        else
            val s = this.size
            val a = this.toIndexed
            @tailrec def loop(idx: Int = 0, acc: Int = 1): Int =
                if idx < s then
                    loop(idx + 1, 31 * acc + a(idx).hashCode())
                else
                    acc
            loop()
end Chunk

object Chunk:

    import internal.*

    sealed abstract class Indexed[T] extends Chunk[T]:

        //////////////////
        // O(1) methods //
        //////////////////

        def apply(i: Int): T

        final def head: T =
            if isEmpty then
                throw new NoSuchElementException
            else
                apply(0)

        final def tail: Indexed[T] =
            if size <= 1 then cachedEmpty.asInstanceOf[Indexed[T]]
            else
                this match
                    case Tail(chunk, offset, size) =>
                        Tail(chunk, offset + 1, size - 1)
                    case c =>
                        Tail(c, 1, size - 1)
    end Indexed

    def empty[T]: Chunk[T] =
        cachedEmpty.asInstanceOf[Chunk[T]]

    def apply[T](values: T*): Chunk[T] =
        from(values)

    def from[T](values: Seq[T]): Chunk[T] =
        if values.isEmpty then empty
        else
            values match
                case seq: IndexedSeq[T] => FromSeq(seq)
                case _                  => Compact(values.toArray(using ClassTag(classOf[Any])))

    def fill[T](n: Int)(v: T): Chunk[T] =
        if n <= 0 then empty
        else
            val array = (new Array[Any](n)).asInstanceOf[Array[T]]
            @tailrec def loop(idx: Int = 0): Unit =
                if idx < n then
                    array(idx) = v
                    loop(idx + 1)
            loop()
            Compact(array)
    end fill

    def collect[T, S](c: Chunk[T < S]): Chunk[T] < S =
        c.map(v => v)

    private[kyo2] object internal:

        val cachedEmpty = Compact(new Array[Any](0))

        case class FromSeq[T](
            seq: IndexedSeq[T]
        ) extends Indexed[T]:
            def size = seq.size
            def apply(i: Int) =
                if i >= size || i < 0 then
                    throw new NoSuchElementException
                else
                    seq(i)

            override def toString = s"Chunk.Indexed(${seq.mkString(", ")})"
        end FromSeq

        case class Compact[T](
            array: Array[T]
        ) extends Indexed[T]:
            def size = array.length
            def apply(i: Int) =
                if i >= size || i < 0 then
                    throw new NoSuchElementException
                else
                    array(i)

            override def toString = s"Chunk.Indexed(${array.mkString(", ")})"
        end Compact

        case class Tail[T](
            chunk: Indexed[T],
            offset: Int,
            size: Int
        ) extends Indexed[T]:
            def apply(i: Int): T  = chunk(i + offset)
            override def toString = s"Chunk(${toSeq.mkString(", ")})"
        end Tail

        case class Drop[T](
            chunk: Chunk[T],
            dropLeft: Int,
            dropRight: Int,
            size: Int
        ) extends Chunk[T]:
            override def toString = s"Chunk(${toSeq.mkString(", ")})"
        end Drop

        case class Append[T](
            chunk: Chunk[T],
            value: T,
            size: Int
        ) extends Chunk[T]:
            override def toString = s"Chunk(${toSeq.mkString(", ")})"
        end Append
    end internal
end Chunk
