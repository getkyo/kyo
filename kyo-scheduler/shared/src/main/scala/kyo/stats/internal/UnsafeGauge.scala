package kyo.stats.internal

import scala.annotation.tailrec

abstract class UnsafeGauge:
    def close(): Unit

object UnsafeGauge:
    val noop =
        new UnsafeGauge:
            def close() = ()

    def all(l: UnsafeGauge*): UnsafeGauge =
        all(l.toList)

    def all(l: List[UnsafeGauge]): UnsafeGauge =
        l.filter(_ ne noop) match
            case Nil =>
                noop
            case h :: Nil =>
                h
            case l =>
                new UnsafeGauge:
                    def close() =
                        @tailrec def loop(c: List[UnsafeGauge]): Unit =
                            if c ne Nil then
                                c.head.close()
                                loop(c.tail)
                        loop(l)
                    end close
end UnsafeGauge
