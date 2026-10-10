import kyo.*

object Main extends KyoApp:
    run {
        Query.outcome.map(outcome => Console.printLine(s"CONSUMER native=$outcome"))
    }
end Main
