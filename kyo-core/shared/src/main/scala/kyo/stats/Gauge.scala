package kyo.stats

import kyo.*
import scala.annotation.tailrec

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
        l.filter(_.unsafe ne noop.unsafe) match
            case Nil =>
                noop
            case h :: Nil =>
                h
            case l =>
                Gauge(
                    new Unsafe:
                        def close() =
                            @tailrec def loop(c: List[Gauge]): Unit =
                                if c ne Nil then
                                    c.head.unsafe.close()
                                    loop(c.tail)
                            loop(l)
                        end close
                )
end Gauge
