package kyo

/** Ownership of the threads that feed a process's stdin.
  *
  * JVM-only, and the split is genuine rather than a way to avoid platform cost. JS feeds stdin
  * through Node streams with no thread of its own, so there is nothing here to own. On Native the
  * threads exist, but interrupting one parked in a blocking read is not dependable, which
  * `ProcessPlatformSpecific` already notes where it routes pipelines through `sh -c` rather than
  * through threads. What is asserted below is specifically that a JVM thread parked in a read is
  * released when the scope closes.
  */
class CommandStdinFeedJvmTest extends kyo.test.Test[Any]:

    "a stdin feed does not outlive the scope that spawned the process" in {
        // The feed runs on a thread of its own, so nothing about the fiber's lifetime reaches it
        // unaided: before the feed was tied to the process, closing the scope left the thread parked
        // in read() for as long as its source withheld bytes, holding that source open with it.
        val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
        val parked = new java.util.concurrent.CountDownLatch(1)

        // A source that never yields a byte and never ends. Blocking is the point: it stands in for
        // a pipe or socket gone quiet, which is when an unowned feed is impossible to observe.
        val source = new java.io.InputStream:
            override def read(): Int =
                parked.countDown()
                new java.util.concurrent.CountDownLatch(1).await()
                -1
            end read
            override def read(b: Array[Byte], off: Int, len: Int): Int = read()
            override def close(): Unit                                 = closed.set(true)

        Scope.run {
            Command("cat").stdin(Process.Input.FromStream(source)).spawn.map { _ =>
                // Wait until the feed is genuinely parked in the read, so what follows is about the
                // scope closing rather than about the feed never having started.
                Sync.defer(parked.await())
            }
        }.andThen {
            // The scope has closed. The feed is interrupted, unwinds, and closes its source.
            assertEventually(Sync.defer(closed.get()))
        }.andThen {
            assert(closed.get(), "the stdin feed outlived the scope that spawned the process")
        }
    }

end CommandStdinFeedJvmTest
