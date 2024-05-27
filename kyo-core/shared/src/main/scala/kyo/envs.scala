package kyo

import kyo.core.*

class Envs[+V] extends Effect[Envs[V]]:
    type Command[T] = Tag[T]

object Envs:
    private case object envs extends Envs[Any]
    private def envs[V]: Envs[V] = envs.asInstanceOf[Envs[V]]

    // Envs.get[String] would normally use a tag of Envs[String]
    // Yet we may provide a Layer with an output of String & Int.
    // This would usually give the handler a tag of Envs[String & Int]
    // However, then handler.tag != kyo.tag in the handle implementation.
    // So we have to use this type erased tag and rely on our `accepts` implementation
    private trait EnvsErased
    private val envsTag: Tag[EnvsErased]     = Tag[EnvsErased]
    private def makeEnvsTag[V]: Tag[Envs[V]] = envsTag.asInstanceOf[Tag[Envs[V]]]

    def get[V](using tag: Tag[V]): V < Envs[V] =
        envs[V].suspend[V](tag)(using makeEnvsTag[V])

    class UseDsl[V]:
        inline def apply[T, S](inline f: V => T < S)(
            using
            inline intersection: Tag.Intersection[V],
            inline tag: Tag[V]
        ): T < (Envs[V] & S) =
            envs[V].suspend[V, T, S](tag, f)(using makeEnvsTag[V])
        end apply
    end UseDsl

    def use[V >: Nothing]: UseDsl[V] =
        new UseDsl[V]

    class RunDsl[V]:
        def apply[T: Flat, S, VS, VR](env: V)(value: T < (Envs[VS] & S))(
            using
            HasEnvs[V, VS] { type Remainder = VR },
            Tag[V]
        ): T < (S & VR) =
            given Tag[Envs[V]] = makeEnvsTag[V]
            envs[V].handle(handler[V])(TypeMap(env), value).asInstanceOf[T < (S & VR)]
        end apply
    end RunDsl

    def run[V >: Nothing]: RunDsl[V] =
        new RunDsl[V]

    def provide[V >: Nothing, T: Flat, S, VS, VR](env: TypeMap[V])(value: T < (Envs[VS] & S))(
        using
        HasEnvs[V, VS] { type Remainder = VR },
        Tag.Intersection[V]
    ): T < (S & VR) =
        given Tag[Envs[V]] = makeEnvsTag[V]
        envs[V].handle(handler[V])(env, value).asInstanceOf[T < (S & VR)]
    end provide

    def provideReverse[V >: Nothing, T: Flat, S, VS, VR](value: T < (Envs[VS] & S))(env: TypeMap[V])(
        using
        HasEnvs[V, VS] { type Remainder = VR },
        Tag.Intersection[V]
    ): T < (S & VR) =
        given Tag[Envs[V]] = makeEnvsTag[V]
        envs[V].handle(handler[V])(env, value).asInstanceOf[T < (S & VR)]
    end provideReverse

    private def handler[V](using intersection: Tag.Intersection[V]) =
        new ResultHandler[TypeMap[V], Tag, Envs[V], Id, Any]:
            override def accepts[T](st: TypeMap[V], command: Tag[T]): Boolean =
                intersection <:< command

            def done[T](st: TypeMap[V], v: T)(using Tag[Envs[V]]) = v

            def resume[T, U: Flat, S2](st: TypeMap[V], command: Tag[T], k: T => U < (Envs[V] & S2))(using Tag[Envs[V]]) =
                Resume(st, k(st.get(using command.asInstanceOf[Tag[Any]]).asInstanceOf[T]))

    sealed trait HasEnvs[V, +VS]:
        type Remainder

    trait LowPriorityHasEnvs:
        given hasEnvs[V, VR]: HasEnvs[V, V & VR] with
            type Remainder = Envs[VR]

    object HasEnvs extends LowPriorityHasEnvs:
        given isEnvs[V]: HasEnvs[V, V] with
            type Remainder = Any
    end HasEnvs

end Envs

// I'm going to delete this and rebase atop Adam's changes once those are in :)
opaque type TypeMap[+R] = Map[Tag[?], Any]

object TypeMap:
    def empty: TypeMap[Any] = Map.empty[Tag[?], Any]

    def apply[A: Tag](a: A): TypeMap[A] =
        Map(Tag[A] -> a)

    def apply[A: Tag, B: Tag](a: A, b: B): TypeMap[A & B] =
        Map(Tag[A] -> a, Tag[B] -> b)

    def apply[A: Tag, B: Tag, C: Tag](a: A, b: B, c: C): TypeMap[A & B & C] =
        Map(Tag[A] -> a, Tag[B] -> b, Tag[C] -> c)

    def apply[A: Tag, B: Tag, C: Tag, D: Tag](a: A, b: B, c: C, d: D): TypeMap[A & B & C & D] =
        Map(Tag[A] -> a, Tag[B] -> b, Tag[C] -> c, Tag[D] -> d)

    extension [R](map: TypeMap[R])
        def get[A >: R](using tag: Tag[A]): A = map(tag).asInstanceOf[A]

        def add[A](value: A)(using tag: Tag[A]): TypeMap[R & A] =
            map.updated(tag, value)

        def union[R0](that: TypeMap[R0]): TypeMap[R & R0] =
            map ++ that

        def tag: Tag.Intersection[?] = Tag.Intersection(map.keys.toSeq)
    end extension

end TypeMap
