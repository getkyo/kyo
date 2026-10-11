package kyo.internal

import OsSignal.Handler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.discard
import scala.annotation.tailrec
import scala.scalanative.posix.signal as posix
import scala.scalanative.unsafe.*
import scala.util.control.NonFatal

/** Delivers POSIX signals to their handlers on a dedicated dispatcher thread.
  *
  * The C handler (`kyo_signal.c`) only writes the signal number to a self-pipe, because a signal can land on any thread at any point,
  * including inside an allocation, a held lock, or a GC safepoint wait, and running managed code there deadlocks the GC or corrupts the
  * heap. The dispatcher thread blocks on the pipe's read end and runs the registered handler as ordinary code.
  *
  * A dispatcher that stops (the pipe fails or ends, or a fatal escapes a handler) restores the default action of every signal it
  * installed, since an installed C handler with nobody draining the pipe would swallow SIGINT and SIGTERM for the rest of the process.
  * Installation and the stop are ordered so that a handler installed while the dispatcher stops is reset too: the signal is recorded
  * before `install` runs and `alive` is checked after it, while the stop clears `alive` before resetting the recorded signals.
  */
final private[internal] class NativeSignalDispatcher(next: () => Int, install: Int => Int, reset: Int => Unit):
    private val handlers  = new ConcurrentHashMap[Int, () => Unit]
    private val installed = ConcurrentHashMap.newKeySet[Int]()
    private val alive     = new AtomicBoolean(true)

    /** Registers `handler` for `signum` and installs the C handler; false when the signal keeps its default action. */
    def register(signum: Int, handler: () => Unit): Boolean =
        discard(handlers.put(signum, handler))
        discard(installed.add(signum))
        val ok = install(signum) == 0
        if alive.get() then ok
        else
            reset(signum)
            false
        end if
    end register

    def run(): Unit =
        try dispatch()
        finally
            alive.set(false)
            installed.forEach(signum => reset(signum))

    @tailrec private def dispatch(): Unit =
        val signum = next()
        if signum >= 0 then
            Maybe(handlers.get(signum)).foreach { handler =>
                try handler()
                catch
                    case NonFatal(e) =>
                        val thread = Thread.currentThread()
                        thread.getUncaughtExceptionHandler.uncaughtException(thread, e)
            }
            dispatch()
        end if
    end dispatch
end NativeSignalDispatcher

private object NativeSignalDispatch:

    private lazy val dispatcher: Maybe[NativeSignalDispatcher] =
        val fd = KyoSignal.kyo_signal_open()
        if fd < 0 then Absent
        else
            val d = new NativeSignalDispatcher(
                () => KyoSignal.kyo_signal_wait(fd),
                signum => KyoSignal.kyo_signal_install(signum),
                signum => discard(KyoSignal.kyo_signal_reset(signum))
            )
            // The dispatcher is a raw thread that no fiber reaches, so its blocking read parks no scheduler worker.
            val thread = new Thread(() => d.run(), "kyo-signal-dispatcher")
            thread.setDaemon(true)
            thread.start()
            Present(d)
        end if
    end dispatcher

    def install(signal: String, signum: CInt, handler: () => Unit): Unit =
        if !dispatcher.exists(_.register(signum, handler)) then
            java.lang.System.err.println(s"kyo: failed to install a handler for signal $signal; it keeps its default action.")
end NativeSignalDispatch

@extern
private object KyoSignal:
    def kyo_signal_open(): CInt                   = extern
    def kyo_signal_install(signum: CInt): CInt    = extern
    def kyo_signal_reset(signum: CInt): CInt      = extern
    @blocking def kyo_signal_wait(fd: CInt): CInt = extern
end KyoSignal

/** Native-specific implementation using POSIX signals via Scala Native. */
private[internal] class OsSignalPlatformSpecific:
    val handle: Handler = new Handler:
        private def signum(signal: String): CInt =
            signal match
                case "INT"  => posix.SIGINT
                case "TERM" => posix.SIGTERM
                case "HUP"  => posix.SIGHUP
                case "USR1" => posix.SIGUSR1
                case "USR2" => posix.SIGUSR2

        override def apply(signalName: String, handler: () => Unit): Unit =
            NativeSignalDispatch.install(signalName, signum(signalName), handler)
end OsSignalPlatformSpecific
