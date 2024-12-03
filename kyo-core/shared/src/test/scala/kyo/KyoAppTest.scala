package kyo

import Tagged.*

class KyoAppTest extends Test:

    "main" in runNotJS {
        val app = new KyoApp:
            run {
                for
                    _ <- Console.println(s"Starting with args [${args.mkString(", ")}]")
                yield "Exit!"
            }

        app.main(Array("arg1", "arg2"))
        succeed
    }
    "multiple runs" taggedAs jvmOnly in run {
        for
            ref <- AtomicInt.init(0)
            app = new KyoApp:
                run { ref.getAndIncrement }
                run { ref.getAndIncrement }
                run { ref.getAndIncrement }

            _    <- IO(app.main(Array.empty))
            runs <- ref.get
        yield assert(runs == 3)
    }

    "exit on error" taggedAs jvmOnly in {
        var exitCode = -1
        def app(fail: Boolean): KyoApp = new KyoApp:
            override def exit(code: Int): Unit = exitCode = code
            run(Abort.when(fail)(new RuntimeException("Aborts!")))
        app(fail = true).main(Array.empty)
        assert(exitCode == 1)
        exitCode = -1
        app(fail = false).main(Array.empty)
        assert(exitCode == -1)
    }

    "effects" taggedAs jvmOnly in {
        def run: Int < (Async & Resource & Abort[Throwable]) =
            for
                _ <- Clock.repeatAtInterval(1.second, 1.second)(())
                i <- Random.nextInt
                _ <- Console.println(s"$i")
                _ <- Clock.now
                _ <- Resource.ensure(())
                _ <- Async.run(())
            yield 1

        import AllowUnsafe.embrace.danger
        assert(KyoApp.Unsafe.runAndBlock(Duration.Infinity)(run) == Result.success(1))
    }
    "failing effects" taggedAs jvmOnly in {
        def run: Unit < (Async & Resource & Abort[Throwable]) =
            for
                _ <- Clock.now
                _ <- Random.nextInt
                _ <- Abort.fail(new RuntimeException("Aborts!"))
            yield ()

        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(run) match
            case Result.Fail(exception: RuntimeException) => assert(exception.getMessage == "Aborts!")
            case _                                        => fail("Unexpected Success...")
    }

    "effect mismatch" taggedAs jvmOnly in {
        assertDoesNotCompile("""
            new KyoApp:
                run(1: Int < Options)
        """)
    }

    "indirect effect mismatch" taggedAs jvmOnly in {
        assertDoesNotCompile("""
            new KyoApp:
                run(Choices.run(1: Int < Options))
        """)
    }

end KyoAppTest
