package kyo.test

import kyo.*
import zio.ZIO
import zio.test.Spec

abstract class KyoSpecDefault extends KyoSpecAbstract[Async & Scope & Abort[Throwable]]:
    final override def run[In](v: => In < (Async & Scope & Abort[Throwable]))(using Frame): ZIO[Environment, Throwable, In] =
        ZIOs.run(Scope.run(v))

    def timeout: Duration = Duration.Infinity

    def spec: Spec[Environment, Any]

end KyoSpecDefault
