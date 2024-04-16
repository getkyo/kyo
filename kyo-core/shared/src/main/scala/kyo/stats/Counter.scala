package kyo.stats

import kyo.*
import kyo.stats.internal.UnsafeCounter

case class Counter(unsafe: UnsafeCounter) extends AnyVal:
    def inc: Unit < IOs =
        IOs(unsafe.inc())
    def add(v: Long): Unit < IOs =
        IOs(unsafe.add(v))
    def add(v: Long, b: Attributes): Unit < IOs =
        IOs(unsafe.add(v, b))
    def attributes(b: Attributes): Counter =
        Counter(unsafe.attributes(b))
end Counter

object Counter:

    val noop: Counter =
        Counter(UnsafeCounter.noop)

    def all(l: List[Counter]): Counter =
        Counter(UnsafeCounter.all(l.map(_.unsafe)))
end Counter
