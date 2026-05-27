package kyo.internal.tasty.snapshot

/** JVM implementation: backed by a ThreadLocal so concurrent snapshot-read fibers each get their own scratch array. */
private[snapshot] object PlatformScratchBuffer:

    private val local: ThreadLocal[Array[Byte]] =
        ThreadLocal.withInitial(() => new Array[Byte](256))

    def get(): Array[Byte] = local.get()

    def set(buf: Array[Byte]): Unit = local.set(buf)

end PlatformScratchBuffer
