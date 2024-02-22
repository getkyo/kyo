package kyo.stats

import kyo.*

case class Gauge(unsafe: Gauge.Unsafe) extends AnyVal:
    def close: Unit < IOs = IOs(unsafe.close())

object Gauge:

    abstract class Unsafe:
        def close(): Unit

    val noop: Gauge =
        Gauge(
            new Unsafe:
                def close() = ()
        )

    def all(l: List[Gauge]): Gauge =
        l.filter(_ != noop) match
            case Nil =>
                noop
            case h :: Nil =>
                h
            case l =>
                Gauge(
                    new Unsafe:
                        def close() =
                            var c = l
                            while c ne Nil do
                                c.head.unsafe.close()
                                c = c.tail
                        end close
                )
end Gauge
