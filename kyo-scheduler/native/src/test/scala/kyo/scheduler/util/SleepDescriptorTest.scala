package kyo.scheduler.util

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.scheduler.BlockingMonitor
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import scala.scalanative.meta.LinktimeInfo
import scala.util.control.NonFatal

/** Holds the Native Sleep to nanosleep by the descriptors it does not allocate.
  *
  * Scala Native's Thread.sleep parks on a pipe (two descriptors per in-flight call), nanosleep on none; the regulator calls Sleep on every
  * jitter probe, so a regression is a hot-path fd cost SleepTest cannot see. A held thread gains none/two endpoints, read off /proc/self/fd; Linux Native only.
  */
class SleepDescriptorTest extends AnyFreeSpec with NonImplicitAssertions {

    "allocates no pipe descriptor while parked" in {
        if (!LinktimeInfo.isLinux)
            cancel("the descriptor table this probe reads back is published by Linux only")

        val entered  = new AtomicBoolean(false)
        val proceed  = new AtomicBoolean(false)
        val threadId = new AtomicLong(0L)
        val thread = new Thread((() => {
            threadId.set(ThreadUserTime.currentThreadId())
            entered.set(true)
            // Spin, don't park, on the barrier: the wait allocates no descriptor, so the baseline captures everything
            // this thread owns except what Sleep is about to allocate.
            while (!proceed.get()) Thread.onSpinWait()
            // The test's interrupt releases this thread; swallow the exception a pipe-based Sleep would raise.
            try Sleep(30000)
            catch { case _: InterruptedException => () }
        }): Runnable)
        thread.setDaemon(true)
        thread.start()
        try {
            while (!entered.get()) Thread.onSpinWait()
            val baseline = pipeEndpoints()
            proceed.set(true)
            // Wait until the thread is actually inside Sleep before reading, using the scheduler's own blocked signal
            // (user CPU time that stopped advancing); reading earlier would miss the allocation.
            val detector = new BlockingMonitor(1)
            val ids      = Array(threadId.get())
            eventually(timeout(scaled(Span(30, Seconds)))) {
                detector.sample(ids, 1)
                assert(detector.isBlocked(0), "the probe thread should have reached Sleep")
            }
            val allocated = pipeEndpoints() -- baseline
            assert(
                allocated.isEmpty,
                s"Sleep parked on pipe descriptors instead of nanosleep: $allocated"
            )
        } finally {
            thread.interrupt()
            ()
        }
    }

    /** Pipe endpoints the process holds open, keyed by their /proc/self/fd symlink target ("pipe:[inode]") not the fd number, so a
      * set difference is meaningful: fd numbers are recycled on close, inodes are not. A descriptor that closes mid-read drops out.
      */
    private def pipeEndpoints(): Set[String] = {
        val entries = new File("/proc/self/fd").list()
        if (entries eq null) Set.empty
        else
            entries.foldLeft(Set.empty[String]) { (acc, fd) =>
                val target =
                    try Files.readSymbolicLink(Paths.get("/proc/self/fd/" + fd)).toString
                    catch { case ex if NonFatal(ex) => "" }
                if (target.startsWith("pipe:")) acc + target else acc
            }
    }
}
