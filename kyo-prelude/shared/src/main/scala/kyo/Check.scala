package kyo

import Ansi.*
import kyo.kernel.*

class CheckFailed(val message: String, val frame: Frame) extends Exception(message):
    override def getMessage() =
        Seq(
            "",
            "──────────────────────────────".dim,
            "Check failed! ".red.bold + message,
            "──────────────────────────────".dim,
            frame.parse.show,
            "──────────────────────────────".dim
        ).mkString("\n")
end CheckFailed

sealed trait Check extends ArrowEffect[Const[CheckFailed], Const[Unit]]

object Check:

    inline def apply(inline condition: Boolean)(using inline frame: Frame): Unit < Check =
        Check(condition, "")

    inline def apply(inline condition: Boolean, inline message: => String)(using inline frame: Frame): Unit < Check =
        if condition then ()
        else ArrowEffect.suspend[Unit](Tag[Check], new CheckFailed(message, frame))

    def runAbort[A: Flat, S](v: A < (Check & S))(using Frame): A < (Abort[CheckFailed] & S) =
        ArrowEffect.handle(Tag[Check], v)(
            [C] => (input, cont) => Abort.fail(input)
        )

    def runChunk[A: Flat, S](v: A < (Check & S))(using Frame): (Chunk[CheckFailed], A) < S =
        ArrowEffect.handle.state(Tag[Check], Chunk.empty[CheckFailed], v)(
            handle = [C] =>
                (input, state, cont) =>
                    (state.append(input), cont(())),
            done = (state, result) => (state, result)
        )

    def runDiscard[A: Flat, S](v: A < (Check & S))(using Frame): A < S =
        ArrowEffect.handle(Tag[Check], v)(
            [C] => (_, cont) => cont(())
        )

end Check
