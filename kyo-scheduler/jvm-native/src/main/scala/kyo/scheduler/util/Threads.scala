package kyo.scheduler.util

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Thread factory for creating consistently named daemon threads.
  *
  * Creates daemon threads with auto-incrementing IDs prefixed by the given name. Optionally accepts a custom thread creation function while
  * maintaining naming.
  *
  * @param name
  *   Base name for created threads
  * @param create
  *   Optional custom thread creation function
  */
object Threads {

    def apply(name: String): ThreadFactory =
        apply(name, new Thread(_))

    def apply(name: String, create: Runnable => Thread): ThreadFactory =
        new ThreadFactory {
            val nextId = new AtomicInteger
            def newThread(r: Runnable): Thread = {
                val t = create(r)
                t.setName(name + "-" + nextId.incrementAndGet)
                t.setDaemon(true)
                t
            }
        }
}
