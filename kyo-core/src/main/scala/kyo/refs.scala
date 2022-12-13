package kyo

import java.util.concurrent.atomic.AtomicReference

import core._
import ios._
import scala.annotation.tailrec

object refs {

  opaque type Ref[T] = AtomicReference[T]

  extension [T](ref: Ref[T]) {

    def get: T > IOs =
      IOs(ref.get)

    def set(v: T): Unit > IOs =
      IOs(ref.set(v))

    def getAndSet(v: T): T > IOs =
      IOs(ref.getAndSet(v))

    def getAndUpdate(f: T => T): T > IOs =
      IOs(ref.getAndUpdate(v => f(v)))

    def updateAndGet(f: T => T): T > IOs =
      IOs(ref.updateAndGet(v => f(v)))

    def modify[U](f: T => (U, T)): U > IOs =
      @tailrec def loop(): U =
        val curr = ref.get()
        f(curr) match {
          case (res, next) =>
            if (ref.compareAndSet(curr, next))
              res
            else
              loop()
        }
      IOs(loop())

    def cas(curr: T, next: T): Boolean > IOs =
      IOs(ref.compareAndSet(curr, next))

    def lazySet(v: T): Unit > IOs =
      IOs(ref.lazySet(v))
  }

  object Ref {
    def apply[T](v: => T): Ref[T] > IOs =
      IOs(new AtomicReference)
  }
}
