import kyo.*

// Never runs: every step of this fixture expects the build to stop before a binary exists.
object Main extends KyoApp:
    run(Console.printLine("CONSUMER unreachable"))
end Main
