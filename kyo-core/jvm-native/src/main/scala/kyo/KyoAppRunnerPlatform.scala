package kyo

/** JVM / Native registration of [[KyoAppRunner]] effects via [[KyoApp.runAndBlock]]. */
trait KyoAppRunnerPlatform:
    self: KyoAppRunnerWithInterrupts =>

    /** Registers an effect to run when [[KyoAppRunner.runInitCode]] executes. */
    final protected def registerEffect[A](effect: => A < (Async & Scope & Abort[Any]))(using Frame, Render[A]): Unit =
        import AllowUnsafe.embrace.danger
        initCode = initCode.appended(() =>
            val result = Sync.Unsafe.evalOrThrow(Abort.run(KyoApp.runAndBlock(runTimeout)(handle(effect))))
            onResult(result)
        )
    end registerEffect
end KyoAppRunnerPlatform
