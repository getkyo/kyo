package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Span")
class JsSpan[A](@JSName("$span") val underlying: Span[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    private given [T]: scala.reflect.ClassTag[T] = scala.reflect.ClassTag[T](classOf[AnyRef])
    def append(x: A) =
        new JsSpan(Span.`:+`(underlying)(x))

    def apply(idx: Int) =
        Span.apply(underlying)(idx)

    def collect[B](pf: PartialFunction[A, B]) =
        new JsSpan(Span.collect(underlying)(pf))

    def collectFirst[B](pf: PartialFunction[A, B]) =
        new JsMaybe(Span.collectFirst(underlying)(pf))

    def concat(suffix: JsSpan[A]) =
        new JsSpan(Span.`++`(underlying)(suffix.underlying))

    def contains(elem: A) =
        Span.contains(underlying)(elem)

    def copyToArray[B](xs: Array[B], start: Int, len: Int) =
        Span.copyToArray(underlying)(xs, start, len)

    def count(p: Function1[A, Boolean]) =
        Span.count(underlying)(p)

    def distinct() =
        new JsSpan(Span.distinct(underlying))

    def distinctBy[B](f: Function1[A, B]) =
        new JsSpan(Span.distinctBy(underlying)(f))

    def drop(n: Int) =
        new JsSpan(Span.drop(underlying)(n))

    def dropRight(n: Int) =
        new JsSpan(Span.dropRight(underlying)(n))

    def dropWhile(p: Function1[A, Boolean]) =
        new JsSpan(Span.dropWhile(underlying)(p))

    def endsWith[B](that: JsSpan[B]) =
        Span.endsWith(underlying)(that.underlying)

    def exists(f: Function1[A, Boolean]) =
        Span.exists(underlying)(f)

    def existsZip[B](b: JsSpan[B], f: Function2[A, B, Boolean]) =
        Span.existsZip(underlying)(b.underlying)(f)

    def existsZip[B, C](b: JsSpan[B], c: JsSpan[C], f: Function3[A, B, C, Boolean]) =
        Span.existsZip(underlying)(b.underlying, c.underlying)(f)

    def filter(p: Function1[A, Boolean]) =
        new JsSpan(Span.filter(underlying)(p))

    def filterNot(p: Function1[A, Boolean]) =
        new JsSpan(Span.filterNot(underlying)(p))

    def find(p: Function1[A, Boolean]) =
        new JsMaybe(Span.find(underlying)(p))

    def flatMap[B](f: Function1[A, Span[B]]) =
        new JsSpan(Span.flatMap(underlying)(f))

    def flatten[B]() =
        new JsSpan(Span.flatten(underlying))

    def fold[B](z: B, op: Function2[B, B, B]) =
        Span.fold(underlying)(z)(op)

    def foldLeft[B](z: B, op: Function2[B, A, B]) =
        Span.foldLeft(underlying)(z)(op)

    def foldRight[B](z: B, op: Function2[A, B, B]) =
        Span.foldRight(underlying)(z)(op)

    def forall(f: Function1[A, Boolean]) =
        Span.forall(underlying)(f)

    def forallZip[B](b: JsSpan[B], f: Function2[A, B, Boolean]) =
        Span.forallZip(underlying)(b.underlying)(f)

    def forallZip[B, C](b: JsSpan[B], c: JsSpan[C], f: Function3[A, B, C, Boolean]) =
        Span.forallZip(underlying)(b.underlying, c.underlying)(f)

    def foreach(f: Function1[A, Any]) =
        Span.foreach(underlying)(f)

    def head() =
        new JsMaybe(Span.head(underlying))

    def indexOf(elem: A, from: Int) =
        new JsMaybe(Span.indexOf(underlying)(elem, from))

    def indexWhere(p: Function1[A, Boolean], from: Int) =
        new JsMaybe(Span.indexWhere(underlying)(p, from))

    def is(other: JsSpan[A]) =
        Span.is(underlying)(other.underlying)

    def isEmpty() =
        Span.isEmpty(underlying)

    def last() =
        new JsMaybe(Span.last(underlying))

    def lastIndexOf(elem: A, end: Int) =
        new JsMaybe(Span.lastIndexOf(underlying)(elem, end))

    def lastIndexWhere(p: Function1[A, Boolean], end: Int) =
        new JsMaybe(Span.lastIndexWhere(underlying)(p, end))

    def map[B](f: Function1[A, B]) =
        new JsSpan(Span.map(underlying)(f))

    def mkString(separator: Predef.String) =
        Span.mkString(underlying)(separator)

    def mkString() =
        Span.mkString(underlying)

    def mkString(start: Predef.String, sep: Predef.String, end: Predef.String) =
        Span.mkString(underlying)(start, sep, end)

    def nonEmpty() =
        Span.nonEmpty(underlying)

    def padTo(len: Int, elem: A) =
        new JsSpan(Span.padTo(underlying)(len, elem))

    def partition(p: Function1[A, Boolean]) =
        Span.partition(underlying)(p)

    def prepend(x: A) =
        new JsSpan(Span.prepend(underlying)(x))

    def reverse() =
        new JsSpan(Span.reverse(underlying))

    def scan(z: A, op: Function2[A, A, A]) =
        new JsSpan(Span.scan(underlying)(z)(op))

    def scanLeft[B](z: B, op: Function2[B, A, B]) =
        new JsSpan(Span.scanLeft(underlying)(z)(op))

    def scanRight[B](z: B, op: Function2[A, B, B]) =
        new JsSpan(Span.scanRight(underlying)(z)(op))

    def size() =
        Span.size(underlying)

    def slice(from: Int, until: Int) =
        new JsSpan(Span.slice(underlying)(from, until))

    def sliding(size: Int, step: Int) =
        Span.sliding(underlying)(size, step)

    def span(p: Function1[A, Boolean]) =
        Span.span(underlying)(p)

    def splitAt(n: Int) =
        Span.splitAt(underlying)(n)

    def startsWith[B](that: JsSpan[B], offset: Int) =
        Span.startsWith(underlying)(that.underlying, offset)

    def tail() =
        new JsMaybe(Span.tail(underlying))

    def take(n: Int) =
        new JsSpan(Span.take(underlying)(n))

    def takeRight(n: Int) =
        new JsSpan(Span.takeRight(underlying)(n))

    def takeWhile(p: Function1[A, Boolean]) =
        new JsSpan(Span.takeWhile(underlying)(p))

    def toArray() =
        Span.toArray(underlying)

    def toArrayUnsafe() =
        Span.toArrayUnsafe(underlying)

    def update(index: Int, elem: A) =
        new JsSpan(Span.update(underlying)(index, elem))


end JsSpan

object JsSpan:
    import kyo.JsFacadeGivens.given
    private given [T]: scala.reflect.ClassTag[T] = scala.reflect.ClassTag[T](classOf[AnyRef])

    @JSExportStatic
    def apply[A](a0: A, a1: A, a2: A) =
        new JsSpan(Span.apply(a0, a1, a2))

    @JSExportStatic
    def apply[A](a0: A) =
        new JsSpan(Span.apply(a0))

    @JSExportStatic
    def apply[A]() =
        new JsSpan(Span.apply())

    @JSExportStatic
    def apply[A](a0: A, a1: A) =
        new JsSpan(Span.apply(a0, a1))

    @JSExportStatic
    def apply[A](a0: A, a1: A, a2: A, a3: A, a4: A) =
        new JsSpan(Span.apply(a0, a1, a2, a3, a4))

    @JSExportStatic
    def apply[A](a0: A, a1: A, a2: A, a3: A) =
        new JsSpan(Span.apply(a0, a1, a2, a3))

    @JSExportStatic
    def concat[A](spans: Seq[Span[A]]) =
        new JsSpan(Span.concat(spans*))

    @JSExportStatic
    def empty[A]() =
        new JsSpan(Span.empty)

    @JSExportStatic
    def from[A](seq: IterableOnce[A]) =
        new JsSpan(Span.from(seq))

    @JSExportStatic
    def fromUnsafe[A](array: Array[A]) =
        new JsSpan(Span.fromUnsafe(array))

    @JSExportStatic
    def iterate[A](start: A, len: Int, f: Function1[A, A]) =
        new JsSpan(Span.iterate(start, len)(f))


end JsSpan