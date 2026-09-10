package kyo

/** Represents a memoization effect for global value initialization.
  *
  * Memo is used to cache the results of expensive computations, allowing them to be reused without re-computation.
  *
  * This effect is specifically designed for initializing global values or caching results of infrequent, expensive operations. For
  * memoization in performance-sensitive code or hot paths, consider using `Async.memoize` or `Cache` in kyo-core instead, which have lower
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

    // Memo and Var[Cache] are the same type inside this scope, so a tag summoned here for either
    // cannot be derived: the macro refuses it. Derived by name, Memo's tag is the one every call
    // site outside uses for this effect, and it is passed explicitly below. A val rather than a
    // given, since a given Tag[Memo] here would answer every Tag[Var[Cache]] query in the scope.
    private val memoTag: Tag[Memo] = Tag.derive[Memo]

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
                            Var.update[Cache](_.updated(input, id, result))(using memoTag, summon[Frame])
                                .map(_ => result)
                        }
            }(using memoTag)
    end apply

    def run[A, S](v: A < (Memo & S))(using Frame): A < S =
        Var.run(empty)(v)(using memoTag, summon[Frame])

    /** Default isolate that combines memoization caches.
      *
      * When the isolation ends, merges any cached results from the isolated computation with the outer cache using the later result on
      * conflicts. This allows memoized results computed in isolation to be reused later.
      */
    given isolate: Isolate[Memo, Any, Memo] = Var.isolate.merge[Cache](using memoTag)((a, b) => Cache(a.map ++ b.map))

end Memo
