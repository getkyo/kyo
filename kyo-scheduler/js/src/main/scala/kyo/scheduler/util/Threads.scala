package kyo.scheduler.util

import java.lang.Thread
import java.util.concurrent.ThreadFactory

object Threads {

    def apply(name: String): ThreadFactory = apply(name, _ => Thread.currentThread())

    def apply(name: String, create: Runnable => Thread): ThreadFactory =
        new ThreadFactory {
            override def newThread(runnable: Runnable): Thread = create(runnable)
        }

}
