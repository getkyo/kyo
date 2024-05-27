package kyo

import kyo.Tag.Intersection
import scala.collection.immutable.HashMap

opaque type Env[+R] = HashMap[Tag[?], Any]
extension [R](self: Env[R])

    private inline def fatal(using t: Tag[?]): Nothing =
        throw new RuntimeException(s"fatal: kyo.Env of contents [$self] missing value of type: [${t.show}].")

    inline def get[A >: R](using inline t: Tag[A]): A =
        self.getOrElse(t, fatal).asInstanceOf[A]

    inline def add[A: Tag](a: A)(using inline t: Tag[A]): Env[R & A] =
        self.updated(t, a)

    def union[R0](that: Env[R0]): Env[R & R0] =
        self ++ that

    inline def size: Int        = self.size
    inline def isEmpty: Boolean = self.isEmpty

    private[kyo] def tag: Intersection[?] = Intersection(self.keySet.toIndexedSeq)
end extension

object Env:
    val empty: Env[Any] = HashMap.empty

    def apply[A: Tag](a: A): Env[A] =
        HashMap(Tag[A] -> a)
    def apply[A: Tag, B: Tag](a: A, b: B): Env[A & B] =
        HashMap(Tag[A] -> a, Tag[B] -> b)
    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C): Env[A & B & C] =
        HashMap(Tag[A] -> a, Tag[B] -> b, Tag[C] -> c)
    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D): Env[A & B & C & D] =
        HashMap(Tag[A] -> a, Tag[B] -> b, Tag[C] -> c, Tag[D] -> d)
end Env
