package kyo.internal

import scala.scalanative.posix.signal as posix
import scala.scalanative.posix.unistd

object OSSignalTestPlatform:
    // A process-directed kill, not raise: the kernel picks any thread that does not block the signal, so the
    // handler lands on scheduler workers mid-allocation as well as on the sender.
    def send(signal: String): Unit =
        val num = signal match
            case "USR1" => posix.SIGUSR1
            case "USR2" => posix.SIGUSR2
        val _ = posix.kill(unistd.getpid(), num)
    end send
end OSSignalTestPlatform
