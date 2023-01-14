package kyo.concurrent

import scheduler._
import kyo.core._
import kyo.ios._

object races {

  class Race[T] extends IOPromise[T] {
    def add(io: => T > IOs): Unit > IOs =
      IOs {
        val f = IOTask(IOs(io))
        interrupts(f)
        f.onComplete(complete(_))
      }
  }
}
