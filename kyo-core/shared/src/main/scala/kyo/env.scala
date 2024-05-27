package kyo

import kyo.Tag.Intersection
import scala.collection.immutable.HashMap

opaque type Env[+R] = HashMap[Tag[?], Any]
extension [R](self: Env[R])

    private def fatal(using t: Tag[?]): Nothing =
        throw new RuntimeException(s"fatal: kyo.Env of contents [$self] missing value of type: [${t.show}].")

    def get[A >: R](using Tag[A]): A =
        getOrElse(fatal)

    def getOrElse[A >: R](default: => A)(using t: Tag[A]): A =
        val result = self.getOrElse(t, null)
        if isNull(result) then
            default
        else
            result.asInstanceOf[A]
        end if
    end getOrElse

    def add[A: Tag](a: A): Env[R & A] =
        self.updated(Tag[A], a)

    def union[R0](that: Env[R0]): Env[R & R0] =
        self ++ that

    def size: Int        = self.size
    def isEmpty: Boolean = self.isEmpty

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
