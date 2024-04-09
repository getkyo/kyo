package kyo.stats

import kyo.*

case class Counter(unsafe: Counter.Unsafe) extends AnyVal:
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

    abstract class Unsafe:
        def inc(): Unit
        def add(v: Long): Unit
        def add(v: Long, b: Attributes): Unit
        def attributes(b: Attributes): Unsafe
    end Unsafe

    val noop: Counter =
        Counter(
            new Unsafe:
                def inc()                       = ()
                def add(v: Long)                = ()
                def add(v: Long, b: Attributes) = ()
                def attributes(b: Attributes)   = this
        )

    def all(l: List[Counter]): Counter =
        l.filter(_.unsafe ne noop.unsafe) match
            case Nil =>
                noop
            case h :: Nil =>
                h
            case l =>
                Counter(
                    new Unsafe:
                        def inc() = add(1)
                        def add(v: Long) =
                            var c = l
                            while c ne Nil do
                                c.head.unsafe.add(v)
                                c = c.tail
                        end add
                        def add(v: Long, b: Attributes) =
                            var c = l
                            while c ne Nil do
                                c.head.unsafe.add(v, b)
                                c = c.tail
                        end add
                        def attributes(b: Attributes) =
                            all(l.map(c => c.attributes(b))).unsafe
                )
end Counter
