// package kyo2.scheduler

// import kyo2.*

// sealed abstract private[kyo2] class IOFiber[+E, +A]:
//     def isDone(): Boolean
//     def interrupts(i: IOPromise[?, ?])(using Frame): Unit
//     def interrupt()(using Frame): Boolean
//     def onComplete(f: Result[E, A] => Unit): Unit
//     def block(deadline: Long)(using frame: Frame): Result[E, A]
// end IOFiber
