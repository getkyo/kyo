package kyo

import java.io.Closeable
import scala.util.Try
import scala.util.control.NonFatal

import kyo.ios._
import kyo.envs._
import kyo.sums._
import kyo.scopes._
import java.util.concurrent.CopyOnWriteArrayList
import org.jctools.queues.MpscUnboundedArrayQueue
import izumi.reflect.Tag

object resources {

  class Finalizer private[resources] () {
    private val closes = new MpscUnboundedArrayQueue[Unit > IOs](3)
    private[resources] def add(close: Unit > IOs): Unit > IOs =
      IOs {
        closes.add(close)
        ()
      }
    private[resources] val run =
      IOs {
        var close = closes.poll()
        while (close != null) {
          IOs.run(close)
          close = closes.poll()
        }
      }
  }

  type Resources = Envs[Finalizer] with IOs

  object Resources {

    private val envs = Envs[Finalizer]

    def ensure[T](f: => Unit > IOs): Unit > Resources =
      envs.get.map(_.add(f))

    def acquire[T <: Closeable](resource: => T): T > Resources = {
      lazy val v = resource
      ensure(IOs(v.close())).andThen(v)
    }

    def run[T, S](v: T > (Resources with S)): T > (IOs with S) = {
      val finalizer = new Finalizer()
      envs.run[T, IOs with S](finalizer)(IOs.ensure(finalizer.run)(v))
    }
  }

  implicit def resourcesScope[E: Tag]: Scopes[Envs[E]] =
    new Scopes[Envs[E]] {
      def sandbox[S1, S2](f: Scopes.Op[S1, S2]) =
        new Scopes.Op[Envs[E] with S1, Envs[E] with (S1 with S2)] {
          def apply[T](v: T > (Envs[E] with S1)) =
            Envs[E].get.map(Envs[E].run[T, S1](_)(v))
        }
    }
}
