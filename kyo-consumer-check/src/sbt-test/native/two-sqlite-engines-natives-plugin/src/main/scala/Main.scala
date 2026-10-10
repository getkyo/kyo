import kyo.*

// Never runs: the link is expected to stop on the duplicate entry points the two engines' shims define. Both URL
// schemes are opened so neither driver is dropped before reaching the linker.
object Main extends KyoApp:
    run {
        val query = sql"SELECT 1".as[Int].run
        for
            sqlite   <- Abort.run[Any](DB.run("sqlite://:memory:")(query))
            doltlite <- Abort.run[Any](DB.run("doltlite://:memory:")(query))
            _        <- Console.printLine(s"CONSUMER engines=$sqlite,$doltlite")
        yield ()
    }
end Main
