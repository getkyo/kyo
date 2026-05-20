package kyo.internal

import OsSignal.Handler
import scala.scalanative.posix.signal as posix
import scala.scalanative.unsafe.*

/** Internal global dispatcher to avoid capturing local state in C callbacks. */
private object NativeSignalDispatch:
    private val registry = scala.collection.mutable.HashMap.empty[Int, () => Unit]

    val cHandler: CFuncPtr1[CInt, Unit] = CFuncPtr1.fromScalaFunction { (sig: CInt) =>
        val cb = registry.synchronized { registry.get(sig) }
        cb.foreach(_.apply())
    }

    def install(signum: CInt, cb: () => Unit): Unit =
        registry.synchronized { registry.update(signum, cb) }
end NativeSignalDispatch

/** Native-specific implementation using POSIX signals via Scala Native. */
private[internal] class OsSignalPlatformSpecific:
    val handle: Handler = new Handler:
        private def signum(signal: String): CInt =
            signal match
                case "INT"  => posix.SIGINT
                case "TERM" => posix.SIGTERM
                case "HUP"  => posix.SIGHUP

        override def apply(signalName: String, handler: () => Unit): Unit =
            val num = signum(signalName)
            NativeSignalDispatch.install(num, handler)
            val _ = posix.signal(num, NativeSignalDispatch.cHandler)
        end apply
end OsSignalPlatformSpecific
