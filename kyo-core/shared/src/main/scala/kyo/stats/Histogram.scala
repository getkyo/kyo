package kyo.stats

import kyo.*

case class Histogram(unsafe: Histogram.Unsafe) extends AnyVal:

    def observe(v: Double): Unit < IOs =
        IOs(unsafe.observe(v))

    def observe(v: Double, b: Attributes): Unit < IOs =
        IOs(unsafe.observe(v, b))

    def attributes(b: Attributes): Histogram =
        Histogram(unsafe.attributes(b))
end Histogram

object Histogram:

    abstract class Unsafe:
        def observe(v: Double): Unit
        def observe(v: Double, b: Attributes): Unit
        def attributes(b: Attributes): Unsafe
    end Unsafe

    val noop: Histogram =
        Histogram(
            new Unsafe:
                def observe(v: Double)                = ()
                def observe(v: Double, b: Attributes) = ()
                def attributes(b: Attributes)         = this
        )

    def all(l: List[Histogram]): Histogram =
        l.filter(_ != noop) match
            case Nil =>
                noop
            case h :: Nil =>
                h
            case l =>
                Histogram(
                    new Unsafe:
                        def observe(v: Double) =
                            var c = l
                            while c ne Nil do
                                c.head.unsafe.observe(v)
                                c = c.tail
                        end observe
                        def observe(v: Double, b: Attributes) =
                            var c = l
                            while c ne Nil do
                                c.head.unsafe.observe(v, b)
                                c = c.tail
                        end observe
                        def attributes(b: Attributes) =
                            all(l.map(_.attributes(b))).unsafe
                )
end Histogram
