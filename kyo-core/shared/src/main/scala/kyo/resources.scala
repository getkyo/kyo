package kyo

import java.io.Closeable
import scala.util.Try
import scala.util.control.NonFatal

import kyo.core._
import kyo.ios._
import kyo.envs._
import kyo.sums._
import kyo.scopes._
import java.util.concurrent.CopyOnWriteArrayList
import org.jctools.queues.MpscUnboundedArrayQueue
import izumi.reflect.Tag
import java.util.concurrent.atomic.AtomicInteger
import org.jctools.queues.MpmcUnboundedXaddArrayQueue

object resources {

  private case object GetFinalizer

  type Resource[T] >: T // = T | GetFinalizer

  final class Resources private[resources] ()
      extends Effect[Resource, Resources] {

    private[resources] val finalizer: Finalizer > Resources =
      suspend(GetFinalizer.asInstanceOf[Resource[Finalizer]])

    def ensure(v: => Unit > IOs): Unit > (IOs with Resources) =
      finalizer.map { f =>
        IOs {
          f.closes.add(IOs(v))
          ()
        }
      }

    def acquire[T <: Closeable](resource: => T): T > (IOs with Resources) = {
      lazy val v = resource
      ensure(v.close()).andThen(v)
    }

    def run[T, S](v: T > (Resources with S)): T > (IOs with S) =
      run[T, S](v, new Finalizer)

    private[resources] def run[T, S](
        v: T > (Resources with S),
        finalizer: Finalizer
    ): T > (IOs with S) = {
      implicit def handler: Handler[Resource, Resources] =
        new Handler[Resource, Resources] {
          def pure[U](v: U) = v
          def apply[U, V, S2](
              m: Resource[U],
              f: U => V > (Resources with S2)
          ): V > (S2 with Resources) =
            m match {
              case GetFinalizer =>
                f(finalizer.asInstanceOf[U])
              case _ =>
                f(m.asInstanceOf[U])
            }
        }
      IOs.ensure(finalizer.run) {
        handle[T, Resources with S](v).asInstanceOf[T > S]
      }
    }
  }
  val Resources = new Resources

  implicit val resourcesScope: Scopes[Resources with IOs] =
    new Scopes[Resources with IOs] {
      def sandbox[S1, S2](f: Scopes.Op[S1, S2]) =
        new Scopes.Op[Resources with IOs with S1, Resources with IOs with (S1 with S2)] {
          def apply[T](v: T > (Resources with IOs with S1)) =
            Resources.finalizer.map { f =>
              IOs {
                var p = f.parties.get()
                while (p > 0 && !f.parties.compareAndSet(p, p + 1)) {
                  p = f.parties.get()
                }
                if (p > 0) {
                  f
                } else {
                  new Finalizer
                }
              }.map(Resources.run[T, IOs with S1](v, _))
            }
        }
    }

  private class Finalizer {
    val closes  = new MpmcUnboundedXaddArrayQueue[Unit > IOs](3)
    val parties = new AtomicInteger(1)
    def run =
      if (parties.decrementAndGet() == 0) {
        var close = closes.poll()
        while (close != null) {
          IOs.run(close)
          close = closes.poll()
        }
      }
  }
}
