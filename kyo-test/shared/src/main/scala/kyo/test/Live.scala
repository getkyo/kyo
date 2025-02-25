package kyo.test

import kyo.*

/** The `Live` trait provides access to the "live" default Kyo services from within Kyo Test for workflows such as printing test results to
  * the console or timing out tests where it is necessary to access the real implementations of these services.
  *
  * The easiest way to access the "live" services is to use the `live` method with a workflow that would otherwise use the test version of
  * the default Kyo services.
  *
  * {{ import kyo.*
  *
  * val realTime = live(Clock.nanoTime) }}
  *
  * The `withLive` method can be used to apply a transformation to a workflow with the live services while ensuring that the workflow itself
  * still runs with the test services.
  */
trait Live:
    def provide[R, E, A](zio: A < Env[R] & Abort[E])(using trace: Trace): A < Env[R] & Abort[E]

object Live:

    val tag: Tag[Live] = Tag[Live]

    final case class Test(zenv: Environment[Clock with Console with System with Random]) extends Live:
        def provide[R, E, A](zio: A < Env[R] & Abort[E])(using trace: Trace): A < Env[R] & Abort[E] =
            DefaultServices.currentServices.locallyWith(_.unionAll(zenv))(zio)

    val default: Layer[Live, Env[Clock with Console with System with Random]] =
        given trace: Trace = Tracer.newTrace
        Layer.scoped {
            for
                zenv <- environment[Clock with Console with System with Random]
                live = Test(zenv)
                _ <- withLiveScoped(live)
            yield live
        }
    end default

    def live[R, E, A](zio: A < Env[R] & Abort[E])(using trace: Trace): A < Env[R] & Abort[E] =
        liveWith(_.provide(zio))

    def withLive[R, E, E1, A, B](zio: A < Env[R] & Abort[E])(f: A < Env[R] & Abort[E] => B < Env[R] & Abort[E1])(using
        trace: Trace
    ): B < Env[R] & Abort[E1] =
        DefaultServices.currentServices.getWith(services => live(f(DefaultServices.currentServices.locally(services)(zio))))

end Live
