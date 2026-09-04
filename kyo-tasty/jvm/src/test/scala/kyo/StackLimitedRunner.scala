package kyo

/** JVM implementation: runs a thunk in a stack-limited thread to guard against unbounded recursion.
  *
  * The limit exists to make a runaway decode fail fast rather than exhaust a default 512KB-1MB stack, so it
  * needs to be small but still large enough for the correct decode to finish. 64KB was too small on
  * Windows-on-ARM, where frames are wider than x86-64's: the thread produced neither a result nor an
  * exception and the caller saw only "no result". 256KB keeps the guard meaningful, still failing a
  * genuinely unbounded recursion almost immediately.
  *
  * JS and Native run the body directly with no limit at all, so the size is a JVM-side guard rather than
  * part of the property under test.
  */
object StackLimitedRunner:

    private val stackBytes = 256 * 1024
    private val joinMillis = 5000

    def run(body: => Unit): Unit =
        var exception: Option[Throwable] = None
        val thread = new Thread(
            null,
            () =>
                try body
                catch case t: Throwable => exception = Some(t),
            "stack-limited",
            stackBytes.toLong
        )
        thread.start()
        thread.join(joinMillis.toLong)
        // Distinguish a thread that never finished from one that finished having recorded nothing.
        // Falling through left the caller asserting on an unset result with no idea which had happened.
        if thread.isAlive then
            throw new AssertionError(
                s"stack-limited thread did not finish within ${joinMillis}ms (stack ${stackBytes / 1024}KB)"
            )
        end if
        exception.foreach(e => throw e)
    end run

end StackLimitedRunner
