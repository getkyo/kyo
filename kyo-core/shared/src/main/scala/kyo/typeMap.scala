package kyo

import kyo.Tag.Intersection
import scala.collection.immutable.HashMap

opaque type TypeMap[+A] = HashMap[Tag[?], Any]
extension [A](self: TypeMap[A])

    private inline def fatal(using t: Tag[?]): Nothing =
        throw new RuntimeException(s"fatal: kyo.TypeMap of contents [$self] missing value of type: [${t.show}].")

    inline def get[B >: A](using inline t: Tag[B]): B =
        self.getOrElse(t, fatal).asInstanceOf[B]

    inline def add[B](b: B)(using inline t: Tag[B]): TypeMap[A & B] =
        self.updated(t, b)

    inline def union[B](that: TypeMap[B]): TypeMap[A & B] =
        self ++ that

    inline def size: Int        = self.size
    inline def isEmpty: Boolean = self.isEmpty

    private[kyo] inline def tag: Intersection[?] = Intersection(self.keySet.toIndexedSeq)
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
