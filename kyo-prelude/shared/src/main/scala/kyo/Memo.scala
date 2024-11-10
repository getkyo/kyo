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

end Memo
