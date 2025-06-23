package kyo

/** Test application for signal interruption testing. This app runs indefinitely until interrupted by a signal.
  */
object TestSignalApp extends KyoApp:
    given Frame = Frame.internal
    run {
        for
            _ <- Console.printLine("TestSignalApp started - waiting for signal")
            _ <- Resource.ensure(ex => Console.printLine(s"TestSignalApp finished: $ex"))
            _ <- Async.never
        yield ()
    }
end TestSignalApp
