package kyo.test

import kyo.*
import zio.ZIO
import zio.test.Spec

abstract class KyoSpecDefault extends KyoSpecAbstract[Async & Resource & Abort[Throwable]]:
    final override def run[In: Flat](v: => In < (Async & Resource & Abort[Throwable]))(using Frame): ZIO[Environment, Throwable, In] =
        ZIOs.run(Resource.run(v))

    def timeout: Duration = Duration.Infinity

    def spec: Spec[Environment, Any]

end KyoSpecDefault
