package kyo

/** Represents a memoization effect for global value initialization.
  *
  * Memo is used to cache the results of expensive computations, allowing them to be reused without re-computation.
  *
  * This effect is specifically designed for initializing global values or caching results of infrequent, expensive operations. For
  * memoization in performance-sensitive code or hot paths, consider using `Async.memoize` or `kyo-cache` instead, which have lower
  * overhead.
  */
opaque type Memo <: Var[Memo.Cache] = Var[Memo.Cache]

object Memo:

    // Used to ensure each memoized function
    // has a different key space
    private[kyo] class MemoIdentity extends Serializable

    private[kyo] case class Cache(map: Map[(Any, Any), Any]):
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

    /** Memoizes a function, caching its results for future use.
      *
      * @param f
      *   The function to memoize
      * @tparam A
      *   The input type of the function
      * @tparam B
      *   The output type of the function
      * @return
      *   A memoized version of the input function
      */
    def apply[A, B, S](f: A => B < S)(using Frame): A => B < (S & Memo) =
        val id = new MemoIdentity
        input =>
            Var.use[Cache] { cache =>
                cache.get(input, id) match
                    case Present(cached) =>
                        cached.asInstanceOf[B]
                    case Absent =>
                        f(input).map { result =>
                            Var.update[Cache](_.updated(input, id, result))
                                .map(_ => result)
                        }
            }
    end apply

    def run[A: Flat, S](v: A < (Memo & S))(using Frame): A < S =
        Var.run(empty)(v)

    /** Default isolate that combines memoization caches.
      *
      * When the isolation ends, merges any cached results from the isolated computation with the outer cache using the later result on
      * conflicts. This allows memoized results computed in isolation to be reused later.
      */
    given isolate: Isolate.Stateful[Memo, Any] = Var.isolate.merge[Cache]((a, b) => Cache(a.map ++ b.map))

end Memo
