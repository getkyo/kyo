package kyo.stats.internal

import kyo.stats.Attributes
import scala.annotation.tailrec

abstract class UnsafeCounter {
    def inc(): Unit
    def add(v: Long): Unit
    def add(v: Long, b: Attributes): Unit
    def attributes(b: Attributes): UnsafeCounter
}

object UnsafeCounter {
    val noop: UnsafeCounter =
        new UnsafeCounter {
            def inc()                                    = ()
            def add(v: Long)                             = ()
            def add(v: Long, b: Attributes)              = ()
            def attributes(b: Attributes): UnsafeCounter = this
        }

    def all(l: List[UnsafeCounter]): UnsafeCounter =
        l.filter(_ ne noop) match {
            case Nil =>
                noop
            case h :: Nil =>
                h
            case l =>
                new UnsafeCounter {
                    def inc() = add(1)
                    def add(v: Long) = {
                        @tailrec def loop(c: List[UnsafeCounter]): Unit =
                            if (c ne Nil) {
                                c.head.add(v)
                                loop(c.tail)
                            }
                        loop(l)
                    }
                    def add(v: Long, b: Attributes) = {
                        @tailrec def loop(c: List[UnsafeCounter]): Unit =
                            if (c ne Nil) {
                                c.head.add(v, b)
                                loop(c.tail)
                            }
                        loop(l)
                    }
                    def attributes(b: Attributes) =
                        all(l.map(c => c.attributes(b)))
                }
        }
}
