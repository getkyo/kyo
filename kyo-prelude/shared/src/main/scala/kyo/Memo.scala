package kyo

/** Represents a memoization effect.
  *
  * Memo is used to cache the results of expensive computations, allowing them to be reused without re-computation.
  *
  * This effect is primarily intended for initializing global values or caching results of infrequent, expensive operations. It is not
  * recommended for use in hot paths or frequently executed code due to potential performance overhead.
  */
opaque type Memo <: Var[Memo.Cache] = Var[Memo.Cache]

object Memo:

    // Used to ensure each memoized function
    // has a different key space
    private[kyo] class MemoIdentity

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

    object isolate:

        /** Creates an isolate that combines memoization caches.
          *
          * When the isolation ends, merges any cached results from the isolated computation with the outer cache using the later result on
          * conflicts. This allows memoized results computed in isolation to be reused later.
          *
          * @return
          *   An isolate that preserves memoized results
          */
        def merge: Isolate[Memo] =
            Var.isolate.merge[Memo.Cache]((m1, m2) => Cache(m1.map ++ m2.map))

        /** Creates an isolate that overwrites the memoization cache.
          *
          * When the isolation ends, replaces the outer cache with the cache from the isolated computation. Earlier cached results are
          * discarded in favor of any new results computed in isolation.
          *
          * @return
          *   An isolate that updates the cache with isolated results
          */
        def update: Isolate[Memo] =
            Var.isolate.update[Memo.Cache]

        /** Creates an isolate that provides a temporary cache.
          *
          * Allows the isolated computation to build and use its own memoization cache, but discards that cache when isolation ends. Results
          * are only cached within the isolation boundary.
          *
          * @return
          *   An isolate that discards the isolated cache
          */
        def discard: Isolate[Memo] =
            Var.isolate.discard[Memo.Cache]
    end isolate
end Memo
