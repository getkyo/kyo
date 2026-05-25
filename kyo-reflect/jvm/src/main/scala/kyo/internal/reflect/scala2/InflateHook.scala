package kyo.internal.reflect.scala2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import kyo.*

/** JVM implementation of InflateHook: decompresses ZLIB-compressed bytes using java.util.zip.InflaterInputStream. */
object InflateHook extends InflateHookImpl:
    def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
        Abort.run[Throwable](Sync.defer {
            val inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))
            val out      = new ByteArrayOutputStream()
            val buf      = new Array[Byte](4096)
            var n        = inflater.read(buf, 0, buf.length)
            while n >= 0 do
                out.write(buf, 0, n)
                n = inflater.read(buf, 0, buf.length)
            inflater.close()
            out.toByteArray()
        }).map:
            case Result.Success(bytes) => bytes
            case Result.Failure(t) =>
                Abort.fail(ReflectError.CorruptedFile("<Scala2Pickle>", 0L, s"ZLIB inflate failed: ${t.getMessage}"))
            case Result.Panic(t) =>
                Abort.panic(t)
end InflateHook
