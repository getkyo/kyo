package kyo.internal

import kyo.discard
import scala.scalajs.js

/** Signal handling on Scala.js.
  *
  * A Node-like host delivers signals as process events, so `process.on("SIGINT", ...)` is the same registration the
  * JVM makes through `sun.misc.Signal`. Without it a Ctrl-C on a command-line application killed the process where
  * every other platform interrupts the run block and lets its finalizers run, and the exit code a supervisor reads
  * was whatever the host chose rather than the 130 or 143 the runner records.
  *
  * A page has no process and no signals, so registration there stays the no-op it can only be. That is not a
  * silently ignored request: nothing in a page can deliver one.
  */
private[internal] class OsSignalPlatformSpecific:

    val handle: OsSignal.Handler =
        if Platform.isNodeLike then NodeProcessSignals else OsSignal.Handler.Noop

    /** Registers on the process, translating the JVM's bare signal name to the host's event name. */
    private object NodeProcessSignals extends OsSignal.Handler:
        def apply(signal: String, handle: () => Unit): Unit =
            val listener: js.Function0[Unit] = () => handle()
            PlatformJs.jsGlobal("process").foreach(process => discard(process.on(s"SIG$signal", listener)))

        override def toString = "Signal.Handler.NodeProcess"
    end NodeProcessSignals

end OsSignalPlatformSpecific
