package kyo.stats.internal

import kyo.stats.Attributes
import scala.annotation.tailrec

abstract class UnsafeHistogram:
    def observe(v: Double): Unit
    def observe(v: Double, b: Attributes): Unit
    def attributes(b: Attributes): UnsafeHistogram
end UnsafeHistogram

object UnsafeHistogram:
    val noop =
        new UnsafeHistogram:
            def observe(v: Double)                = ()
            def observe(v: Double, b: Attributes) = ()
            def attributes(b: Attributes)         = this

    def all(l: List[UnsafeHistogram]): UnsafeHistogram =
        l.filter(_ ne noop) match
            case Nil =>
                noop
            case h :: Nil =>
                h
            case l =>
                new UnsafeHistogram:
                    def observe(v: Double) =
                        @tailrec def loop(c: List[UnsafeHistogram]): Unit =
                            if c ne Nil then
                                c.head.observe(v)
                                loop(c.tail)
                        loop(l)
                    end observe
                    def observe(v: Double, b: Attributes) =
                        @tailrec def loop(c: List[UnsafeHistogram]): Unit =
                            if c ne Nil then
                                c.head.observe(v, b)
                                loop(c.tail)
                        loop(l)
                    end observe
                    def attributes(b: Attributes) =
                        all(l.map(_.attributes(b)))
end UnsafeHistogram
