package kyobench

import kyo.*
import kyo.kernel.*
import kyo.kernel.internal.Eval

/** The shared HandleSites shape written against kernel2's region surface: `handleCont` takes a done clause where the older kernel's
  * `handle` took one clause, and the result is evaluated with `Eval(...)` rather than `.eval`. Same site count, same nesting, same
  * continuation shape, so the compile-cost driver is the same and only the spelling differs.
  */
object HandleSites:

    sealed trait Ask  extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    sealed trait Ask2 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]

    def ask: Int < Ask   = ArrowEffect.suspend[Any](Tag[Ask], ())
    def ask2: Int < Ask2 = ArrowEffect.suspend[Any](Tag[Ask2], ())

    def run0: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 0).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run1: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run2: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 2).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run3: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 3).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run4: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 4).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run5: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 5).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run6: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 6).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run7: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 7).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run8: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 8).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run9: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 9).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run10: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 10).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def run11: Int =
        Eval(ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 11).map(a => ask.map(b => a + b)))([X] => (_, cont) => cont(1), a => a))

    def nested0: Int =
        val inner = ArrowEffect.handleCont(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1), a => a)
        Eval(ArrowEffect.handleCont(Tag[Ask2], inner)([X] => (_, cont) => cont(2), a => a))
    end nested0

    def nested1: Int =
        val inner = ArrowEffect.handleCont(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1), a => a)
        Eval(ArrowEffect.handleCont(Tag[Ask2], inner)([X] => (_, cont) => cont(2), a => a))
    end nested1

    def nested2: Int =
        val inner = ArrowEffect.handleCont(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1), a => a)
        Eval(ArrowEffect.handleCont(Tag[Ask2], inner)([X] => (_, cont) => cont(2), a => a))
    end nested2

    def nested3: Int =
        val inner = ArrowEffect.handleCont(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1), a => a)
        Eval(ArrowEffect.handleCont(Tag[Ask2], inner)([X] => (_, cont) => cont(2), a => a))
    end nested3

end HandleSites
