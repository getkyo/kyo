package kyo.concurrent

import kyo._
import kyo.ios._
import kyo.tries._
import kyo.concurrent.fibers._
import scala.concurrent.duration.Duration
import com.github.benmanes.caffeine
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import scala.util.Success
import scala.util.Failure

object caches {

  import Cache._

  class Cache(private[caches] val store: Store) {
    def memo[T, S, U](
        f: T => U > S
    )(implicit id: sourcecode.Enclosing): T => U > (Fibers with IOs with S) = {
      (v: T) =>
        Fibers.initPromise[U].map { p =>
          val key = (id, v)
          IOs[U, Fibers with S] {
            store.get(key, _ => p) match {
              case `p` =>
                IOs.ensure {
                  p.interrupt.map {
                    case true =>
                      IOs(store.invalidate(key))
                    case false =>
                      ()
                  }
                } {
                  Tries.run[U, S](f(v)).map {
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
  }

  object Cache {
    type Store = caffeine.cache.Cache[(sourcecode.Enclosing, Any), Promise[Any]]
  }

  object Caches {

    case class Builder(private[caches] val b: Caffeine[Any, Any]) extends AnyVal {
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

    def init(f: Builder => Builder): Cache > IOs =
      IOs {
        new Cache(
            f(new Builder(Caffeine.newBuilder())).b
              .build[(sourcecode.Enclosing, Any), Promise[Any]]()
        )
      }
  }
}
