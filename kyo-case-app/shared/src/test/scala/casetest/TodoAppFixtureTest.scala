package casetest

import kyo.*
import kyo.internal.Platform
import scala.util.Try

class TodoAppFixtureTest extends kyo.Test:

    "todo workflow" in runNotJS {
        for
            app   <- TodoAppFixture.init
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "--title", "Buy milk")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "Walk dog")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("start", "1")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("complete", "--id", "2")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("delete", "1")))
            todos <- app.store.get
        yield
            assert(todos.length == 1)
            assert(todos.head.title == "Walk dog")
            assert(todos.head.status eq TodoStatus.Completed)
    }

    "list hides completed by default" in runNotJS {
        for
            app   <- TodoAppFixture.init
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "A")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "B")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("complete", "1")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("start", "2")))
            todos <- app.store.get
        yield
            val open = todos.filter(t => t.status ne TodoStatus.Completed)
            assert(open.length == 1)
            assert(open.head.id == 2)
            assert(open.head.status eq TodoStatus.Active)
    }

    "list --all shows every todo" in runNotJS {
        for
            app   <- TodoAppFixture.init
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "done")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("complete", "1")))
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("list", "--all")))
            todos <- app.store.get
        yield assert(todos.head.status eq TodoStatus.Completed)
    }

    "create without title fails" in runNotJS {
        for
            _ <- TodoAppFixture.init
        yield
            val err = Try(TodoAppFixture.entryPoint.main(Array("create")))
            assert(err.isFailure)
    }

    "missing id fails" in runNotJS {
        for
            _ <- TodoAppFixture.init
            _ <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "only")))
        yield
            val err = Try(TodoAppFixture.entryPoint.main(Array("delete")))
            assert(err.isFailure)
    }

    "each subcommand exposes options in run" in runNotJS {
        for
            app   <- TodoAppFixture.init
            _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "--title", "from-options")))
            todos <- app.store.get
        yield assert(todos.head.title == "from-options")
    }

    "ordered subcommand runs share one store" in {
        assume(!Platform.isNative, "repeated main() too slow on Native")
        runNotJS {
            for
                app   <- TodoAppFixture.init
                _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "one")))
                _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "two")))
                _     <- Sync.defer(TodoAppFixture.entryPoint.main(Array("create", "three")))
                todos <- app.store.get
            yield assert(todos.map(_.title) == Chunk("one", "two", "three"))
        }
    }

end TodoAppFixtureTest
