package kyo.test

import kyo.*
import scala.concurrent.duration.*
import zio.ZIO
import zio.test.Spec

abstract class KyoSpecDefault extends KyoSpecAbstract[KyoApp.Effects]:
    final override def run[In](v: => In < KyoApp.Effects)(using Flat[In < KyoApp.Effects]): ZIO[Environment, Throwable, In] =
        ZIO.fromFuture { implicit ec => IOs.run(KyoApp.runFiber(timeout)(v).toFuture).map(_.get) }

    def timeout: Duration = Duration.Inf

    def spec: Spec[Environment, Any]

end KyoSpecDefault
