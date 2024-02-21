package kyo

import scala.concurrent.duration.Duration
import com.github.benmanes.caffeine
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import scala.util.Success
import scala.util.Failure
import scala.runtime.AbstractFunction1

import Cache._

class Cache(private[kyo] val store: Store) {
  def memo[T, U, S](
      f: T => U < S
  ): T => U < (Fibers & S) =
    new AbstractFunction1[T, U < (Fibers & S)] {
      def apply(v: T) =
        Fibers.initPromise[U].map { p =>
          val key = (this, v)
          IOs[U, Fibers & S] {
            store.get(key, _ => p.asInstanceOf[Promise[Any]]) match {
              case `p` =>
                IOs.ensure {
                  p.interrupt.map {
                    case true =>
                      IOs(store.invalidate(key))
                    case false =>
                      ()
                  }
                } {
                  IOs.attempt[U, S](f(v)).map {
                    case Success(v) =>
                      p.complete(v)
                        .unit.andThen(v)
                    case Failure(ex) =>
                      IOs(store.invalidate(key))
                        .andThen(p.complete(IOs.fail(ex)))
                        .unit.andThen(IOs.fail(ex))
                  }
                }
              case p2 =>
                p2.asInstanceOf[Promise[U]].get
            }
          }
        }
    }

  def memo2[T1, T2, S, U](
      f: (T1, T2) => U < S
  )(implicit
      ft1: Flat[T1 < Any],
      ft2: Flat[T2 < Any],
      fu: Flat[U < Any]
  ): (T1, T2) => U < (Fibers & S) = {
    val m = memo[(T1, T2), U, S](f.tupled)
    (t1, t2) => m((t1, t2))
  }

  def memo3[T1, T2, T3, S, U](
      f: (T1, T2, T3) => U < S
  )(implicit
      ft1: Flat[T1 < Any],
      ft2: Flat[T2 < Any],
      ft3: Flat[T3 < Any],
      fu: Flat[U < Any]
  ): (T1, T2, T3) => U < (Fibers & S) = {
    val m = memo[(T1, T2, T3), U, S](f.tupled)
    (t1, t2, t3) => m((t1, t2, t3))
  }

  def memo4[T1, T2, T3, T4, S, U](
      f: (T1, T2, T3, T4) => U < S
  )(implicit
      ft1: Flat[T1 < Any],
      ft2: Flat[T2 < Any],
      ft3: Flat[T3 < Any],
      ft4: Flat[T4 < Any],
      fu: Flat[U < Any]
  ): (T1, T2, T3, T4) => U < (Fibers & S) = {
    val m = memo[(T1, T2, T3, T4), U, S](f.tupled)
    (t1, t2, t3, t4) => m((t1, t2, t3, t4))
  }
}

object Cache {
  type Store = caffeine.cache.Cache[Any, Promise[Any]]
}

object Caches {

  case class Builder(private[kyo] val b: Caffeine[Any, Any]) extends AnyVal {
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
  }

  def init(f: Builder => Builder): Cache < IOs =
    IOs {
      new Cache(
          f(new Builder(Caffeine.newBuilder())).b
            .build[Any, Promise[Any]]()
      )
    }
}
