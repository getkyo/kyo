package kyo

import kyo.kernel.ArrowEffect

class MaskUnionScratchTest extends Test:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    object Ask:
        inline def ask(using Frame): Int < Ask = ArrowEffect.suspend[Int](Tag[Ask], ())
        def run[A, S](v: A < (Ask & S))(answer: Int)(using Frame): A < S =
            ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(answer))
    end Ask

    sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]
    object Say:
        inline def say(s: String)(using Frame): Unit < Say = ArrowEffect.suspend[Unit](Tag[Say], s)
        def run[A, S](v: A < (Say & S))(buf: scala.collection.mutable.ListBuffer[String])(using Frame): A < S =
            ArrowEffect.handleCont(Tag[Say], v)([C] =>
                (input, cont) =>
                    buf += input
                    cont(()))
    end Say

    "one mask of an intersection masks both effects past inner handlers" in {
        val inner                = scala.collection.mutable.ListBuffer[String]()
        val outer                = scala.collection.mutable.ListBuffer[String]()
        val v: Int < (Ask & Say) = Ask.ask.map(a => Say.say("s").map(_ => a))
        val masked               = Mask[Ask & Say](v)
        val innerHandled         = Say.run(Ask.run(masked)(1))(inner)
        val out                  = Mask.run(innerHandled)
        val r                    = Say.run(Ask.run(out)(42))(outer)
        assert(r.eval == 42)
        assert(inner.isEmpty)
        assert(outer.toList == List("s"))
    }
end MaskUnionScratchTest
