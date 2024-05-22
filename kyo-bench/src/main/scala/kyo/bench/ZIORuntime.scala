package kyo.bench

import zio.*

object ZIORuntime:
    def fromLayerWithFinalizer[R](layer: ZLayer[Any, Any, R]): (Runtime[R], () => Unit) =
        Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe.run {
                for
                    scope   <- Scope.make
                    env     <- layer.build(scope)
                    runtime <- ZIO.runtime[R].provideEnvironment(env)
                yield (runtime, () => runtime.unsafe.run(scope.close(Exit.unit)).getOrThrowFiberFailure())
            }.getOrThrowFiberFailure()
        }
end ZIORuntime
