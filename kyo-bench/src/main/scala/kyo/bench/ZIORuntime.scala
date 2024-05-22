package kyo.bench

import zio.*

object ZIORuntime:
    def fromLayer[R](layer: ZLayer[Any, Any, R]): Runtime[R] =
        Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe.run {
                for
                    env     <- layer.build(Scope.global)
                    runtime <- ZIO.runtime[R].provideEnvironment(env)
                yield runtime
            }.getOrThrowFiberFailure()
        }

    lazy val default = fromLayer(ZLayer.succeed(Runtime.default))
end ZIORuntime
