package kyo

import Cache.*
import com.github.benmanes.caffeine
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import scala.runtime.AbstractFunction1
import scala.util.Failure
import scala.util.Success

class Cache(private[kyo] val store: Store):
    def memo[A, B: Flat, S](
        f: A => B < S
    ): A => B < (Async & S) =
        (v: A) =>
            Promise.init[Throwable, B].map { p =>
                val key = (this, v)
                IO[B, Async & S] {
                    val p2 = store.get(key, _ => p.asInstanceOf[Promise[Nothing, Any]])
                    if p.equals(p2) then
                        IO.ensure {
                            p.interrupt.map {
                                case true =>
                                    IO(store.invalidate(key))
                                case false =>
                                    ()
                            }
                        } {
                            Abort.run[Throwable](f(v)).map {
                                case Result.Success(v) =>
                                    p.complete(Result.Success(v))
                                        .unit.andThen(v)
                                case r =>
                                    IO(store.invalidate(key))
                                        .andThen(p.complete(r))
                                        .unit.andThen(r.getOrThrow)
                            }
                        }
                    else
                        p2.asInstanceOf[Promise[Nothing, B]].get
                    end if
                }
            }

    def memo2[T1, T2, S, B: Flat](
        f: (T1, T2) => B < S
    ): (T1, T2) => B < (Async & S) =
        val m = memo[(T1, T2), B, S](f.tupled)
        (t1, t2) => m((t1, t2))
    end memo2

    def memo3[T1, T2, T3, S, B: Flat](
        f: (T1, T2, T3) => B < S
    ): (T1, T2, T3) => B < (Async & S) =
        val m = memo[(T1, T2, T3), B, S](f.tupled)
        (t1, t2, t3) => m((t1, t2, t3))
    end memo3

    def memo4[T1, T2, T3, T4, S, B: Flat](
        f: (T1, T2, T3, T4) => B < S
    ): (T1, T2, T3, T4) => B < (Async & S) =
        val m = memo[(T1, T2, T3, T4), B, S](f.tupled)
        (t1, t2, t3, t4) => m((t1, t2, t3, t4))
    end memo4
end Cache

object Cache:
    type Store = caffeine.cache.Cache[Any, Promise[Nothing, Any]]

    case class Builder(private[kyo] val b: Caffeine[Any, Any]) extends AnyVal:
        def maxSize(v: Int): Builder =
            copy(b.maximumSize(v))
        def weakKeys(): Builder =
            copy(b.weakKeys())
        def weakValues(): Builder =
            copy(b.weakValues())
        def expireAfterAccess(d: Duration): Builder =
            copy(b.expireAfterAccess(d.toMillis, TimeUnit.MILLISECONDS))
        def expireAfterWrite(d: Duration): Builder =
            copy(b.expireAfterWrite(d.toMillis, TimeUnit.MILLISECONDS))
        def initialCapacity(v: Int) =
            copy(b.initialCapacity(v))
        def refreshAfterWrite(d: Duration) =
            copy(b.refreshAfterWrite(d.toMillis, TimeUnit.MILLISECONDS))
    end Builder

    def init(f: Builder => Builder)(using Frame): Cache < IO =
        IO {
            new Cache(
                f(new Builder(Caffeine.newBuilder())).b
                    .build[Any, Promise[Nothing, Any]]()
            )
        }
end Cache
