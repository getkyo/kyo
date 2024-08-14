package kyo

import kyo.Tag
import kyo.kernel.*

opaque type Memo = Var[Memo.Cache]

object Memo:

    // Used to ensure each memoized function
    // has a different key space
    private[kyo] class MemoIdentity

    private[kyo] class Cache(map: Map[(Any, Any), Any]):
        def get[A](input: A, id: MemoIdentity): Maybe[Any] =
            val key = (input, id)
            if map.contains(key) then
                Maybe(map(key))
            else Maybe.empty
        end get
        def updated[A, B](input: A, id: MemoIdentity, value: B): Cache =
            Cache(map.updated((input, id), value))
    end Cache

    private val empty = Cache(Map.empty)

    def apply[A, B, S](f: A => B < S)(using Frame): A => B < (S & Memo) =
        val id = new MemoIdentity
        input =>
            Var.use[Cache] { cache =>
                cache.get(input, id) match
                    case Maybe.Defined(cached) =>
                        cached.asInstanceOf[B]
                    case Maybe.Empty =>
                        f(input).map { result =>
                            Var.update[Cache](_.updated(input, id, result))
                                .map(_ => result)
                        }
            }
    end apply

    def run[A: Flat, S](v: A < (Memo & S)): A < S =
        Var.run(empty)(v)

end Memo
