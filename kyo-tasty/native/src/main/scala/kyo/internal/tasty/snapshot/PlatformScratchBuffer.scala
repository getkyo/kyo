package kyo.internal.tasty.snapshot

/** Native implementation: single-threaded, so a plain module-level var is sufficient. */
private[snapshot] object PlatformScratchBuffer:

    private var buf: Array[Byte] = new Array[Byte](256)

    def get(): Array[Byte] = buf

    def set(b: Array[Byte]): Unit = buf = b

end PlatformScratchBuffer
