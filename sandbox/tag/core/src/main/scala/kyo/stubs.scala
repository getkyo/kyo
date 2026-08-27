package kyo

// Minimal stand-ins for the kyo-data types Tag depends on. None of them carries any of the
// soundness under test; they only need to compile and behave.

opaque type Span[+A] = IArray[Any]
object Span:
    def from[A](xs: IterableOnce[A]): Span[A] = IArray.from[Any](xs)
    def empty[A]: Span[A]                     = IArray.empty[Any]
    def forallZip[A, B](a: Span[A], b: Span[B])(f: (A, B) => Boolean): Boolean =
        a.length == b.length && a.indices.forall(i => f(a(i).asInstanceOf[A], b(i).asInstanceOf[B]))
    def forallZip[A, B, C](a: Span[A], b: Span[B], c: Span[C])(f: (A, B, C) => Boolean): Boolean =
        a.length == b.length && b.length == c.length &&
            a.indices.forall(i => f(a(i).asInstanceOf[A], b(i).asInstanceOf[B], c(i).asInstanceOf[C]))
    extension [A](self: Span[A])
        private def arr: IArray[Any]                                  = self: IArray[Any]
        def size: Int                                                 = arr.length
        def isEmpty: Boolean                                          = arr.length == 0
        def nonEmpty: Boolean                                         = arr.length != 0
        def apply(i: Int): A                                          = java.lang.reflect.Array.get(arr, i).asInstanceOf[A]
        def map[B](f: A => B): Span[B]                                = IArray.from[Any](arr.iterator.map(a => f(a.asInstanceOf[A])))
        def foreach(f: A => Unit): Unit                               = arr.iterator.foreach(a => f(a.asInstanceOf[A]))
        def exists(f: A => Boolean): Boolean                          = arr.iterator.exists(a => f(a.asInstanceOf[A]))
        def forall(f: A => Boolean): Boolean                          = arr.iterator.forall(a => f(a.asInstanceOf[A]))
        def mkString(sep: String): String                             = arr.iterator.mkString(sep)
        def mkString(start: String, sep: String, end: String): String = arr.iterator.mkString(start, sep, end)
        def is(that: Span[A]): Boolean                                = arr eq (that: IArray[Any])
        def toSeq: Seq[A]                                             = arr.iterator.toSeq.asInstanceOf[Seq[A]]
    end extension
end Span

type Chunk[A] = Vector[A]
object Chunk:
    def empty[A]: Chunk[A] = Vector.empty
extension [A](self: Chunk[A]) def append(a: A): Chunk[A] = self.appended(a)
class ChunkBuilder[A]:
    private val b = Vector.newBuilder[A]
    def addOne(a: A): this.type =
        b.addOne(a)
        this
    def result(): Chunk[A] = b.result()
end ChunkBuilder

sealed abstract class Maybe[+A] extends Iterable[A]
case object Absent extends Maybe[Nothing]:
    def iterator: Iterator[Nothing] = Iterator.empty
final case class Present[+A](value: A) extends Maybe[A]:
    def iterator: Iterator[A] = Iterator.single(value)
object Maybe:
    def apply[A](a: A): Maybe[A] = if a.asInstanceOf[AnyRef] eq null then Absent else Present(a)
    def empty[A]: Maybe[A]       = Absent
    extension [A](self: Maybe[A])
        def isDefined: Boolean = self.nonEmpty
end Maybe

def bug(msg: String): Nothing = throw new IllegalStateException("kyo bug: " + msg)
inline def discard[A](inline a: A): Unit =
    val _ = a
    ()
