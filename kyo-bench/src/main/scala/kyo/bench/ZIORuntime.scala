package kyo.bench

import zio.*

extension (parent: ZLayer[Any, Any, Any])
    def merge[R](child: ZLayer[Any, Any, R])(using Tag[R]): ZLayer[Any, Any, R] =
        if parent ne ZLayer.empty then
            parent ++ child
        else
            child

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
