package kyo.internal.reflect.scala2

import kyo.*

/** Native implementation of InflateHook: not available on Scala Native; always fails with NotImplemented. */
object InflateHook extends InflateHookImpl:
    def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
        Abort.fail(ReflectError.NotImplemented("Scala 2 Scala-attribute (ZLIB) inflation is not available on Scala Native"))
end InflateHook
