package kyo

import Cache.*
import com.github.benmanes.caffeine
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit

/** A caching utility class that provides memoization functionality via Caffeine.
  *
  * Each memoized function created by this cache has its own isolated keyspace. This means that different memoized functions, even if they
  * have the same input types, will not interfere with each other's cached results. The isolation is achieved by including a reference to
  * the cache instance itself in the key, along with the input value.
  *
  * @param store
  *   The underlying cache store
  */
class Cache(private[kyo] val store: Store) extends Serializable:

    /** Memoizes a function with a single argument.
      *
      * @tparam A
      *   The input type
      * @tparam B
      *   The output type
      * @tparam S
      *   The effect type
      * @param f
      *   The function to memoize
      * @return
      *   A memoized version of the input function
      */
    def memo[A, B, S](
        f: A => B < S
    )(using Frame): A => B < (Async & S) =
        (v: A) =>
            Promise.initWith[Throwable, B] { p =>
                val key = (this, v)
                Sync[B, Async & S] {
                    val p2 = store.get(key, _ => p.asInstanceOf[Promise[Nothing, Any]])
                    if p.equals(p2) then
                        Sync.ensure {
                            p.interrupt.map {
                                case true =>
                                    Sync(store.invalidate(key))
                                case false =>
                                    ()
                            }
                        } {
                            Abort.run[Throwable](f(v)).map {
                                case Result.Success(v) =>
                                    p.complete(Result.Success(v))
                                        .andThen(v)
                                case r =>
                                    Sync(store.invalidate(key))
                                        .andThen(p.complete(r))
                                        .andThen(r.getOrThrow)
                            }
                        }
                    else
                        p2.asInstanceOf[Promise[Nothing, B]].get
                    end if
                }
            }

    /** Memoizes a function with two arguments.
      *
      * @tparam T1
      *   The first input type
      * @tparam T2
      *   The second input type
      * @tparam S
      *   The effect type
      * @tparam B
      *   The output type
      * @param f
      *   The function to memoize
      * @return
      *   A memoized version of the input function
      */
    def memo2[T1, T2, S, B](
        f: (T1, T2) => B < S
    )(using Frame): (T1, T2) => B < (Async & S) =
        val m = memo[(T1, T2), B, S](f.tupled)
        (t1, t2) => m((t1, t2))
    end memo2

    /** Memoizes a function with three arguments.
      *
      * @tparam T1
      *   The first input type
      * @tparam T2
      *   The second input type
      * @tparam T3
      *   The third input type
      * @tparam S
      *   The effect type
      * @tparam B
      *   The output type
      * @param f
      *   The function to memoize
      * @return
      *   A memoized version of the input function
      */
    def memo3[T1, T2, T3, S, B](
        f: (T1, T2, T3) => B < S
    )(using Frame): (T1, T2, T3) => B < (Async & S) =
        val m = memo[(T1, T2, T3), B, S](f.tupled)
        (t1, t2, t3) => m((t1, t2, t3))
    end memo3

    /** Memoizes a function with four arguments.
      *
      * @tparam T1
      *   The first input type
      * @tparam T2
      *   The second input type
      * @tparam T3
      *   The third input type
      * @tparam T4
      *   The fourth input type
      * @tparam S
      *   The effect type
      * @tparam B
      *   The output type
      * @param f
      *   The function to memoize
      * @return
      *   A memoized version of the input function
      */
    def memo4[T1, T2, T3, T4, S, B](
        f: (T1, T2, T3, T4) => B < S
    )(using Frame): (T1, T2, T3, T4) => B < (Async & S) =
        val m = memo[(T1, T2, T3, T4), B, S](f.tupled)
        (t1, t2, t3, t4) => m((t1, t2, t3, t4))
    end memo4
end Cache

object Cache:

    /** The type of the underlying cache store. */
    type Store = caffeine.cache.Cache[Any, Promise[Nothing, Any]]

    /** A builder class for configuring Cache instances.
      *
      * @param b
      *   The underlying Caffeine builder
      */
    case class Builder(private[kyo] val b: Caffeine[Any, Any]) extends AnyVal:
        /** Sets the maximum size of the cache.
          *
          * @param v
          *   The maximum number of entries the cache may contain
          * @return
          *   An updated Builder
          */
        def maxSize(v: Int): Builder =
            copy(b.maximumSize(v))

        /** Specifies that cache should use weak references for keys.
          *
          * @return
          *   An updated Builder
          */
        def weakKeys(): Builder =
            copy(b.weakKeys())

        /** Specifies that cache should use weak references for values.
          *
          * @return
          *   An updated Builder
          */
        def weakValues(): Builder =
            copy(b.weakValues())

        /** Specifies that each entry should be automatically removed from the cache once a fixed duration has elapsed after the entry's
          * creation, or the most recent replacement of its value.
          *
          * @param d
          *   The length of time after an entry is created that it should be automatically removed
          * @return
          *   An updated Builder
          */
        def expireAfterAccess(d: Duration): Builder =
            copy(b.expireAfterAccess(d.toMillis, TimeUnit.MILLISECONDS))

        /** Specifies that each entry should be automatically removed from the cache once a fixed duration has elapsed after the entry's
          * creation, the most recent replacement of its value, or its last access.
          *
          * @param d
          *   The length of time after an entry is last accessed that it should be automatically removed
          * @return
          *   An updated Builder
          */
        def expireAfterWrite(d: Duration): Builder =
            copy(b.expireAfterWrite(d.toMillis, TimeUnit.MILLISECONDS))

        /** Sets the minimum total size for the internal data structures.
          *
          * @param v
          *   The minimum total size for the internal data structures
          * @return
          *   An updated Builder
          */
        def initialCapacity(v: Int) =
            copy(b.initialCapacity(v))

        /** Specifies that active entries are eligible for automatic refresh once a fixed duration has elapsed after the entry's creation,
          * or the most recent replacement of its value.
          *
          * @param d
          *   The duration after which an entry should be considered stale
          * @return
          *   An updated Builder
          */
        def refreshAfterWrite(d: Duration) =
            copy(b.refreshAfterWrite(d.toMillis, TimeUnit.MILLISECONDS))

    end Builder

    /** Initializes a new Cache instance with the given configuration.
      *
      * @param f
      *   A function that configures the Cache using a Builder
      * @return
      *   A new Cache instance wrapped in an Sync effect
      */
    def init(f: Builder => Builder)(using Frame): Cache < Sync =
        Sync {
            new Cache(
                f(new Builder(Caffeine.newBuilder())).b
                    .build[Any, Promise[Nothing, Any]]()
            )
        }
end Cache
