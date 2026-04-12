package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Chunk")
class JsChunk[A](@JSName("$chun") val underlying: Chunk[A]) extends js.Object:
    import kyo.JsFacadeGivens.given
    private given [T]: scala.reflect.ClassTag[T] = scala.reflect.ClassTag[T](classOf[AnyRef])
    def append[B](b: B) =
        new JsChunk(underlying.append(b))

    def appended[B](b: B) =
        new JsChunk(underlying.appended(b))

    def apply(index: Int) =
        underlying.apply(index)

    def changes[B](first: JsMaybe[B]) =
        new JsChunk(underlying.changes(first.underlying))

    def changes() =
        new JsChunk(underlying.changes)

    def collect[B](pf: PartialFunction[A, B]) =
        new JsChunk(underlying.collect(pf))

    def concat[B](other: JsChunk[B]) =
        new JsChunk(underlying.concat(other.underlying))

    def copyTo[B](array: Array[B], start: Int) =
        underlying.copyTo(array, start)

    def copyTo[B](array: Array[B], start: Int, elements: Int) =
        underlying.copyTo(array, start, elements)

    def drop(n: Int) =
        new JsChunk(underlying.drop(n))

    def dropLeft(n: Int) =
        new JsChunk(underlying.dropLeft(n))

    def dropLeftAndRight(left: Int, right: Int) =
        new JsChunk(underlying.dropLeftAndRight(left, right))

    def dropRight(n: Int) =
        new JsChunk(underlying.dropRight(n))

    def dropWhile(p: Function1[A, Boolean]) =
        new JsChunk(underlying.dropWhile(p))

    def filter(pred: Function1[A, Boolean]) =
        new JsChunk(underlying.filter(pred))

    def foldLeft[B](z: B, op: Function2[B, A, B]) =
        underlying.foldLeft(z)(op)

    def foreach[U](f: Function1[A, U]) =
        underlying.foreach(f)

    def headMaybe() =
        new JsMaybe(underlying.headMaybe)

    def isEmpty() =
        underlying.isEmpty

    def iterableFactory() =
        underlying.iterableFactory

    def iterator() =
        underlying.iterator

    def knownSize() =
        underlying.knownSize

    def last() =
        underlying.last

    def lastMaybe() =
        new JsMaybe(underlying.lastMaybe)

    def length() =
        underlying.length

    def map[B](f: Function1[A, B]) =
        new JsChunk(underlying.map(f))

    def slice(from: Int, until: Int) =
        new JsChunk(underlying.slice(from, until))

    def take(n: Int) =
        new JsChunk(underlying.take(n))

    def takeRight(n: Int) =
        new JsChunk(underlying.takeRight(n))

    def takeWhile(pred: Function1[A, Boolean]) =
        new JsChunk(underlying.takeWhile(pred))

    def toArray[B]() =
        underlying.toArray

    def toIndexed[B]() =
        underlying.toIndexed


end JsChunk

object JsChunk:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def empty[A]() =
        new JsChunk(Chunk.empty)

    @JSExportStatic
    def from[A](source: Option[A]) =
        new JsChunk(Chunk.from(source))

    @JSExportStatic
    def newBuilder[A]() =
        Chunk.newBuilder


end JsChunk