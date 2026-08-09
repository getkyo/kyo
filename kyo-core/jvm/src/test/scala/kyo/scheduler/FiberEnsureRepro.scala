package kyo.scheduler

// scratch: ensure failure paths inside a fiber, runner-free; removed when resolved
import kyo.*
import kyo.kernel.internal.Context

object FiberEnsureRepro:
    given Frame = Frame.internal

    def main(args: Array[String]): Unit =
        def block(name: String)(task: IOPromise[?, ?]): Unit =
            val deadline = java.lang.System.currentTimeMillis() + 5000
            while task.poll().isEmpty && java.lang.System.currentTimeMillis() < deadline do
                Thread.onSpinWait()
            if task.poll().isEmpty then println(s"$name: WEDGE")
        end block

        var called1 = false
        val t1 = IOTask(
            Abort.run[Any](Sync.ensure { called1 = true }(Sync.defer[Int, Any](throw new RuntimeException("boom")))),
            Context.empty
        )
        block("throw-fiber")(t1)
        println(s"throw-fiber: result=${t1.poll()} called=$called1")

        var called2 = false
        val t2 = IOTask(
            Abort.run[String](Sync.ensure { called2 = true }(Abort.fail("fail").map(_ => 42))),
            Context.empty
        )
        block("abort-fiber")(t2)
        println(s"abort-fiber: result=${t2.poll()} called=$called2")

        var seen: Maybe[Result.Error[Any]] = Maybe.Absent
        val t3 = IOTask(
            Abort.run[String](Sync.ensure((e: Maybe[Result.Error[Any]]) => seen = e)(Abort.fail("fail").map(_ => 42))),
            Context.empty
        )
        block("outcome-fiber")(t3)
        println(s"outcome-fiber: result=${t3.poll()} seen=$seen")
        var called4                  = false
        var inner4: Result[Any, Int] = Result.succeed(0)
        val t4 = IOTask(
            Abort.run[Any](Abort.catching[Throwable] {
                Abort.run[Any](Sync.ensure { called4 = true }(Sync.defer[Int, Any](throw new RuntimeException("boom")))).map { r =>
                    inner4 = r
                    ()
                }
            }),
            Context.empty
        )
        block("leafwrap-throw")(t4)
        println(s"leafwrap-throw: result=${t4.poll()} called=$called4 inner=$inner4")

        var called5                  = false
        var inner5: Result[Any, Int] = Result.succeed(0)
        val t5 = IOTask(
            Abort.run[Any](Abort.catching[Throwable] {
                Scope.run {
                    Abort.run[Any](Sync.ensure { called5 = true }(Sync.defer[Int, Any](throw new RuntimeException("boom")))).map { r =>
                        inner5 = r
                        ()
                    }
                }
            }),
            Context.empty
        )
        block("leafwrap-scope-throw")(t5)
        println(s"leafwrap-scope-throw: result=${t5.poll()} called=$called5 inner=$inner5")

        var called6                  = false
        var inner6: Result[Any, Int] = Result.succeed(0)
        val t6 = IOTask(
            Abort.run[Any](Abort.catching[Throwable] {
                Abort.run[String](Sync.ensure { called6 = true }(Abort.fail("fail").map(_ => 42))).map { r =>
                    inner6 = r
                    ()
                }
            }),
            Context.empty
        )
        block("leafwrap-abort")(t6)
        println(s"leafwrap-abort: result=${t6.poll()} called=$called6 inner=$inner6")

        var called7 = false
        var inband7 = false
        val t7 = IOTask(
            Abort.run[String](Sync.ensure { called7 = true }(Abort.fail("fail").map(_ => 42))).map { r =>
                inband7 = called7
                r
            },
            Context.empty
        )
        block("inband-abort")(t7)
        println(s"inband-abort: called=$called7 inbandSawRelease=$inband7")

        var called8 = false
        var inband8 = false
        val t8 = IOTask(
            Abort.run[Any](Sync.ensure { called8 = true }(Sync.defer[Int, Any](throw new RuntimeException("boom")))).map { r =>
                inband8 = called8
                r
            },
            Context.empty
        )
        block("inband-throw")(t8)
        println(s"inband-throw: called=$called8 inbandSawRelease=$inband8")

        println("done")
    end main
end FiberEnsureRepro
