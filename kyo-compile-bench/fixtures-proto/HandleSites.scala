package kyobench

import kyo.Tag
import kyo.kernel.proto.*

object HandleSites:

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    sealed trait Ask2 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())
    def ask2: Int < Ask2 = ArrowEffect.suspend[Any](Tag[Ask2], ())

    def run0: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 0).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run1: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 1).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run2: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 2).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run3: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 3).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run4: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 4).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run5: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 5).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run6: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 6).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run7: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 7).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run8: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 8).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run9: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 9).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run10: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 10).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def run11: Int =
        Eval(ArrowEffect.handle(Tag[Ask], ask.map(_ + 11).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1),
            a => a
        ))

    def nested1: Int =
        val inner = ArrowEffect.handle(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1), a => a)
        Eval(ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(2), a => a))

    def nested2: Int =
        val inner = ArrowEffect.handle(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1), a => a)
        Eval(ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(2), a => a))

    def nested3: Int =
        val inner = ArrowEffect.handle(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1), a => a)
        Eval(ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(2), a => a))

end HandleSites
