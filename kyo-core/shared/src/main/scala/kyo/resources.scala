package kyo

import java.io.Closeable
import java.util.ArrayList
import kyo.core.*
import resourcesInternal.*

sealed abstract class Resources private[kyo] ()
    extends Effect[Resource, Resources]:

    private[kyo] val finalizer: Finalizer < Resources =
        suspend(GetFinalizer.asInstanceOf[Resource[Finalizer]])

    def ensure(v: => Unit < IOs): Unit < (IOs & Resources) =
        finalizer.map(_.put(IOs(v)))

    def acquire[T <: Closeable](resource: => T): T < (IOs & Resources) =
        lazy val v = resource
        ensure(v.close()).andThen(v)

    def run[T, S](v: T < (Resources & S))(
        using f: Flat[T < (Resources & S)]
    ): T < (IOs & S) =
        val finalizer = new Finalizer
        given handler: Handler[Resource, Resources, Any] =
            new Handler[Resource, Resources, Any]:
                def pure[U: Flat](v: U) = v
                def apply[U, V: Flat, S2](
                    m: Resource[U],
                    f: U => V < (Resources & S2)
                ): V < (S2 & Resources) =
                    m match
                        case GetFinalizer =>
                            f(finalizer.asInstanceOf[U])
                        case _ =>
                            f(m.asInstanceOf[U])
        IOs.ensure(finalizer.run) {
            handle[T, Resources & S, Any](v).asInstanceOf[T < S]
        }
    end run
end Resources
object Resources extends Resources

private[kyo] object resourcesInternal:
    type Resource[T] >: T // = T | GetFinalizer

    private[kyo] case object GetFinalizer
    private[kyo] class Finalizer extends ArrayList[Unit < IOs]:
        def put(close: Unit < IOs): Unit < IOs =
            IOs {
                add(close)
                ()
            }
        val run: Unit < IOs =
            IOs {
                while size() > 0 do IOs.run(remove(0))
            }
    end Finalizer
end resourcesInternal
