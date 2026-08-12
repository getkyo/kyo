package kyobench

import kyo.*
import kyo.kernel.*

object HandleSites:

    sealed trait Ask extends ArrowEffect[[X] =>> Unit, [X] =>> Int]
    sealed trait Ask2 extends ArrowEffect[[X] =>> Unit, [X] =>> Int]

    def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())
    def ask2: Int < Ask2 = ArrowEffect.suspend[Any](Tag[Ask2], ())

    def run0: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 0).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run1: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 1).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run2: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 2).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run3: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 3).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run4: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 4).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run5: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 5).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run6: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 6).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run7: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 7).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run8: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 8).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run9: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 9).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run10: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 10).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def run11: Int =
        ArrowEffect.handle(Tag[Ask], ask.map(_ + 11).map(a => ask.map(b => a + b)))(
            [X] => (_, cont) => cont(1)
        ).eval

    def nested0: Int =
        val inner = ArrowEffect.handle(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1))
        ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(2)).eval

    def nested1: Int =
        val inner = ArrowEffect.handle(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1))
        ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(2)).eval

    def nested2: Int =
        val inner = ArrowEffect.handle(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1))
        ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(2)).eval

    def nested3: Int =
        val inner = ArrowEffect.handle(Tag[Ask], ask.map(a => ask2.map(b => a + b)))([X] => (_, cont) => cont(1))
        ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(2)).eval

end HandleSites
