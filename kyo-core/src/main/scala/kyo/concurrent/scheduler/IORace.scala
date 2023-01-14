package kyo.concurrent.scheduler

import kyo.core._
import kyo.ios._

class IORace[T] extends IOPromise[T] {

  inline def add(io: => T > IOs): Unit = {
    val f = IOTask(IOs(io))
    interrupts(f)
    f.onComplete(complete(_))
  }
}
