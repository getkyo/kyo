package kyo

import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory

object scheduler {
  abstract class Scheduler {
    def apply(v: => Unit): Unit
  }

  private val factory = new ThreadFactory {
    override def newThread(r: Runnable): Thread =
      val t = new Thread(r)
      t.setDaemon(true)
      t
  }

  object fixed {
    def apply(threads: Int): Scheduler =
      apply(threads, factory)

    def apply(threads: Int, factory: ThreadFactory): Scheduler =
      Scheduler(Executors.newFixedThreadPool(threads))

    val scheduler = apply(
        Runtime.getRuntime().availableProcessors()
    )
    inline given Scheduler = scheduler
  }

  object never {
    val scheduler = new Scheduler {
      def apply(v: => Unit) = {}
    }
    inline given Scheduler = scheduler
  }

  object immediate {
    val scheduler = new Scheduler {
      def apply(v: => Unit) = v
    }
    inline given Scheduler = scheduler
  }

  object cached {
    def apply(): Scheduler =
      apply(factory)
    def apply(factory: ThreadFactory): Scheduler =
      Scheduler(Executors.newCachedThreadPool(factory))
    val scheduler          = apply()
    inline given Scheduler = scheduler
  }

  object default {
    inline given Scheduler = cached.scheduler
  }

  object Scheduler {
    def apply(e: Executor): Scheduler =
      new Scheduler {
        def apply(v: => Unit) =
          e.execute(() => v)
      }
  }

}
