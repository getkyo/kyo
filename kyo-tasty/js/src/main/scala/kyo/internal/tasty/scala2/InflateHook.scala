package kyo.internal.tasty.scala2

import kyo.*

/** JS implementation of InflateHook: not available on JS; always fails with NotImplemented. */
object InflateHook extends InflateHookImpl:
    def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Abort.fail(TastyError.NotImplemented("Scala 2 Scala-attribute (ZLIB) inflation is not available on Scala.js"))
end InflateHook
