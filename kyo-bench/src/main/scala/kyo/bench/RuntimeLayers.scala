package kyo.bench

import zio.*

object RuntimeLayers:
    given Unsafe = Unsafe.unsafe(identity)

    private val withoutFinalization = ZLayer.succeed(Scope.global)

    private val kExecutor = new Executor:
        val scheduler = kyo.scheduler.Scheduler.get

        def metrics(using unsafe: Unsafe) = None

        def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean =
            scheduler.schedule(kyo.scheduler.Task(runnable.run()))
            true

    val kyoExecutor: ZLayer[Any, Nothing, Unit] =
        Runtime.setExecutor(kExecutor) ++ Runtime.setBlockingExecutor(kExecutor)

    def makeRuntime[A](layer: ZLayer[Any, Any, A]): Runtime[A] =
        Runtime.default.unsafe.run {
            layer.toRuntime.provideLayer(withoutFinalization)
        }.getOrThrowFiberFailure()
end RuntimeLayers
