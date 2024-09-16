package kyo.test

import kyo.*
import zio.ZIO
import zio.test.Spec

abstract class KyoSpecDefault extends KyoSpecAbstract[KyoApp.Effects]:
    final override def run[In: Flat](v: => In < KyoApp.Effects)(using Frame): ZIO[Environment, Throwable, In] =
        ZIOs.run(Resource.run(v))

    def timeout: Duration = Duration.Infinity

    def spec: Spec[Environment, Any]

end KyoSpecDefault
