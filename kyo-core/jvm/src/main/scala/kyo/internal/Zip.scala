package kyo.internal

/** The compression primitives `StreamCompression` drives, backed by `java.util.zip`.
  *
  * The JVM ships zlib, so this is a thin pass-through. The one thing it adds is `remainingBytes`: `java.util.zip.Inflater` reports what it
  * did not consume as a count into the buffer last handed to it, and the drivers want the bytes.
  */
private[kyo] object Zip:

    type DataFormatException = java.util.zip.DataFormatException

    final class Deflater(level: Int, noWrap: Boolean):
        private val under = new java.util.zip.Deflater(level, noWrap)

        def setStrategy(value: Int): Unit                                     = under.setStrategy(value)
        def setInput(bytes: Array[Byte]): Unit                                = under.setInput(bytes)
        def finish(): Unit                                                    = under.finish()
        def deflate(target: Array[Byte], off: Int, len: Int, flush: Int): Int = under.deflate(target, off, len, flush)
        def getBytesRead: Long                                                = under.getBytesRead()
        def end(): Unit                                                       = under.end()
    end Deflater

    final class Inflater(noWrap: Boolean):
        private val under             = new java.util.zip.Inflater(noWrap)
        private var last: Array[Byte] = Array.empty

        def setInput(bytes: Array[Byte]): Unit =
            last = bytes
            under.setInput(bytes)

        def needsInput: Boolean               = under.needsInput()
        def finished: Boolean                 = under.finished()
        def inflate(target: Array[Byte]): Int = under.inflate(target)
        def getBytesWritten: Long             = under.getBytesWritten()
        def end(): Unit                       = under.end()

        def remainingBytes: Array[Byte] =
            val remaining = under.getRemaining
            if remaining <= 0 then Array.empty
            else java.util.Arrays.copyOfRange(last, last.length - remaining, last.length)
        end remainingBytes
    end Inflater

    final class Crc32:
        private val under = new java.util.zip.CRC32

        def update(bytes: Array[Byte]): Unit = under.update(bytes)
        def getValue: Long                   = under.getValue()
        def reset(): Unit                    = under.reset()
    end Crc32
end Zip
