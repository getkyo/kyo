package kyo.internal.tasty.scala2

import kyo.*

/** JS implementation of InflateHook: delegates to PortableInflate, the pure-Scala RFC 1950 inflate. */
object InflateHook extends InflateHookImpl:
    def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        // flow-allow: §839 case 3; PortableInflate.inflate is a pure-compute inflate with no shared state.
        Sync.Unsafe.defer:
            try kyo.internal.tasty.scala2.PortableInflate.inflate(compressed)
            catch
                case ex: kyo.internal.tasty.scala2.PortableInflate.InflateException =>
                    Abort.fail(TastyError.MalformedSection("Scala2Inflate", ex.getMessage, ex.byteOffset))
end InflateHook
