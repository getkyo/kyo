package protodemo

import kyo.Const
import kyo.Frame
import kyo.Tag
import kyo.proto.Loop
import kyo.proto.debug.DebugSession
import kyo.proto.kernel.*

/** The three `ProtoBench` shapes, traceable.
  *
  * `suspensionBaseline` and `handleLoopAnswersInPlace` are the rows that run 2x to 2.6x the pre-stack baseline and about 4x `kyo.kernel`.
  * `suspensionFusesContinuation` runs the same ten thousand suspensions under the same handler and is at parity. The three differ only in
  * how the continuation reaches the region, so tracing them side by side is where the difference has to show.
  *
  * {{{
  * sbt 'kyo-kernelJVM/runMain protodemo.SuspensionDebug map 3'      // suspensionBaseline's shape
  * sbt 'kyo-kernelJVM/runMain protodemo.SuspensionDebug with 3'     // suspensionFusesContinuation's shape
  * sbt 'kyo-kernelJVM/runMain protodemo.SuspensionDebug loop 3'     // handleLoopAnswersInPlace's shape
  * sbt 'kyo-kernelJVM/runMain protodemo.SuspensionDebug map 600 quiet'   // counts only, no step log
  * }}}
  *
  * `quiet` swaps the console tracer for one that only counts, which is what you want past a handful of iterations: the step log is a few
  * lines per hook.
  *
  * The shapes here stand on the public surface only; the tracer session that runs them lives inside kyo (`kyo.proto.debug`), because
  * exercising the kernel's private Debugger seam is what that package is for.
  *
  * One caveat on what a trace can and cannot say. An installed debugger answers `enter()` with the inherited `true`, and
  * `Safepoint.enterPark` takes that as "run this application inline" without draining, so a traced run stops deferring at the budget
  * boundary. Production defers there and replenishes on the unwind. So a trace shows the step sequence faithfully and does not show the
  * deferral rhythm. Pass `guarded` to make the tracer answer `false` instead, which drives the same branch the uninstalled case drives and
  * restores that rhythm.
  */
object SuspensionDebug:

    sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]
    def ask(using Frame): Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    def main(args: Array[String]): Unit =
        val shape = if args.length > 0 then args(0) else "map"
        val depth = if args.length > 1 then args(1).toInt else 3
        val quiet = args.contains("quiet")
        val prod  = args.contains("guarded")

        // handed to the session as a thunk: nodes report their own allocation from their
        // constructors, so construction has to happen inside the installed window
        def build(): Int < Any =
            def mapped(i: Int): Int < Ask =
                if i > depth then i else ask.map(a => mapped(i + a))
            def fused(i: Int): Int < Ask =
                if i > depth then i else ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => fused(i + a))
            shape match
                case "with" => ArrowEffect.handleCont(Tag[Ask], fused(0))([C] => (_, cont) => cont(1), a => a)
                case "loop" => ArrowEffect.handleLoop(Tag[Ask], mapped(0))([C] => _ => Loop.continue((), 1: Int < Any), a => a)
                case _      => ArrowEffect.handleCont(Tag[Ask], mapped(0))([C] => (_, cont) => cont(1), a => a)
            end match
        end build

        println(s"shape=$shape depth=$depth quiet=$quiet guarded=$prod")
        val out = DebugSession.run(quiet, prod)(build)
        println(s"RESULT $out   (expected ${depth + 1})")
    end main
end SuspensionDebug
