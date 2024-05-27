package kyo

import kyo.Tag.Intersection
import scala.collection.immutable.HashMap

opaque type TypeMap[+R] = HashMap[Tag[?], Any]
extension [R](self: TypeMap[R])

    private inline def fatal(using t: Tag[?]): Nothing =
        throw new RuntimeException(s"fatal: kyo.TypeMap of contents [$self] missing value of type: [${t.show}].")

    inline def get[A >: R](using inline t: Tag[A]): A =
        self.getOrElse(t, fatal).asInstanceOf[A]

    inline def add[A: Tag](a: A)(using inline t: Tag[A]): TypeMap[R & A] =
        self.updated(t, a)

    def union[R0](that: TypeMap[R0]): TypeMap[R & R0] =
        self ++ that

    inline def size: Int        = self.size
    inline def isEmpty: Boolean = self.isEmpty

    private[kyo] def tag: Intersection[?] = Intersection(self.keySet.toIndexedSeq)
end extension

object TypeMap:
    val empty: TypeMap[Any] = HashMap.empty

    def apply[A: Tag](a: A): TypeMap[A] =
        HashMap(Tag[A] -> a)
    def apply[A: Tag, B: Tag](a: A, b: B): TypeMap[A & B] =
        HashMap(Tag[A] -> a, Tag[B] -> b)
    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C): TypeMap[A & B & C] =
        HashMap(Tag[A] -> a, Tag[B] -> b, Tag[C] -> c)
    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D): TypeMap[A & B & C & D] =
        HashMap(Tag[A] -> a, Tag[B] -> b, Tag[C] -> c, Tag[D] -> d)
end TypeMap
