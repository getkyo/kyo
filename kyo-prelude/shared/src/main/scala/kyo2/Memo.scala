package kyo2

import kyo.Tag
import kyo2.kernel.*

opaque type Memo = Var[Memo.Cache]

object Memo:

    private[kyo2] case class Cache(map: Map[(Any, Any), Any])

    private val empty = Cache(Map.empty)

    def apply[A, B, S](f: A => B < S)(using Frame): A => B < (S & Memo) =
        val token = new Object
        a =>
            val key = (a, token)
            Var.use[Cache] { cache =>
                cache.map.get(key) match
                    case Some(cached) => cached.asInstanceOf[B]
                    case None =>
                        f(a).map { result =>
                            Var.update[Cache](c => c.copy(c.map + (key -> result)))
                                .map(_ => result)
                        }
            }
    end apply

    def run[A, S](v: A < (Memo & S)): A < S =
        Var.run(empty)(v)

end Memo
