package kyo.kernel.internal

/** Safepoint's thread operations on the JVM and Native.
  *
  * Both platforms provide the full `java.lang.Thread` API this module needs, so `Handle`
  * is `Thread` itself and every operation forwards to it directly. Each `inline def` is
  * spliced at its call site, so `Threads.current()` compiles to exactly
  * `Thread.currentThread()`: no extra call, no wrapper allocation.
  */
private[kernel] object Threads:

    type Handle = Thread

    inline def current(): Handle = Thread.currentThread()

    inline def id(handle: Handle): Long = handle.threadId()

    inline def isAlive(handle: Handle): Boolean = handle.isAlive()

end Threads
