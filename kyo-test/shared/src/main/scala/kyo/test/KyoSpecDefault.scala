package kyo.test

import kyo.*
import kyo.test.interop.*
import zio.ZIO
import zio.test.Spec

abstract class KyoSpecDefault extends KyoSpecAbstract[KyoApp.Effects]:
    final override def run[In](v: => In < KyoApp.Effects)(using Flat[In < KyoApp.Effects]): ZIO[Environment, Throwable, In] =
        ZIO.fromKyoFiber(KyoApp.runFiber(timeout)(v)).flatMap(ZIO.fromTry)

    def timeout: Duration = Duration.Infinity

    def spec: Spec[Environment, Any]

end KyoSpecDefault
