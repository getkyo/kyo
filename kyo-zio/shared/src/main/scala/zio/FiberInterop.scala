package zio

extension [E, A](fiber: Fiber.Runtime[E, A])
    def unsafeInterrupt()(using trace: Trace, u: Unsafe): Unit =
        fiber.tellInterrupt(Cause.Interrupt(FiberId.None, StackTrace(FiberId.None, Chunk(trace))))
