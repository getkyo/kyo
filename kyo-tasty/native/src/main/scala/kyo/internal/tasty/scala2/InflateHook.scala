package kyo.internal.tasty.scala2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import kyo.*

/** Native implementation of InflateHook: decompresses ZLIB-compressed bytes using java.util.zip.InflaterInputStream (available in
  * scala-native javalib).
  */
object InflateHook extends InflateHookImpl:
    def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Sync.defer:
            val result: Array[Byte] < Abort[TastyError] =
                try
                    val inflater = new InflaterInputStream(new ByteArrayInputStream(compressed))
                    val out      = new ByteArrayOutputStream()
                    val buf      = new Array[Byte](4096)
                    var n        = inflater.read(buf, 0, buf.length)
                    while n >= 0 do
                        out.write(buf, 0, n)
                        n = inflater.read(buf, 0, buf.length)
                    inflater.close()
                    out.toByteArray()
                catch
                    case ex: java.util.zip.ZipException =>
                        Abort.fail(TastyError.MalformedSection("Scala2Inflate", ex.getMessage, 0L))
                    case ex: java.io.IOException =>
                        Abort.fail(TastyError.CorruptedFile("Scala2Inflate", 0L, ex.getMessage))
            result
end InflateHook
