package kyo.scheduler

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object Threads:

    def apply(name: String): ThreadFactory =
        new ThreadFactory

    def apply(name: String, create: Runnable => Thread): ThreadFactory =
        new ThreadFactory
end Threads
