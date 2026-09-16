package kyo

/** Feeding a process's stdin from a source that has no bytes yet.
  *
  * JS-only, and the split is genuine. On the JVM a feed runs on a carrier of its own and parks in `read` until bytes
  * arrive, which is what `CommandStdinFeedJvmTest` asserts about. JS has one thread and no parking: a source backed
  * by another process fills from the event loop, so a feed that keeps asking without ever returning to that loop asks
  * forever, and the bytes it is waiting for can never arrive.
  *
  * A page is the other JS host and it has no process table at all, so there is nothing there to feed; the leaf says
  * so rather than failing on the spawn.
  */
class CommandStdinFeedJsTest extends kyo.test.Test[Any]:

    "a stdin feed whose source has no bytes yet lets the event loop deliver them".notBrowser in {
        given AllowUnsafe = AllowUnsafe.embrace.danger
        // The source withholds its output briefly, so the first read of it finds nothing. That is the only shape that
        // separates a feed which yields from one which spins: a source with bytes already buffered hides the
        // difference, and every other way to give stdin (a string, a Span, a Stream) arrives fully buffered.
        Scope.run {
            for
                source <- Command("sh", "-c", "sleep 0.1; printf 'fed by another process'").spawn
                out    <- Command("cat").stdin(Process.Input.FromStream(source.unsafe.stdoutJava)).text
            yield assert(out == "fed by another process")
        }
    }

end CommandStdinFeedJsTest
