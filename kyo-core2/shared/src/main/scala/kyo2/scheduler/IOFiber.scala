package kyo2.scheduler

import kyo2.*

abstract private[kyo2] class IOFiber[+A]:
    def isDone(): Boolean
    def interrupts(i: IOPromise[?])(using Frame): Unit
    def interrupt()(using Frame): Boolean
    def onComplete(f: Result[A] => Unit): Unit
    def block(deadline: Long)(using frame: Frame): Result[A]
end IOFiber
