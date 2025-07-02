package example

import kyo.*

class Issue1232 extends Test:
    "Sync.ensure" in run {
        val result = typeCheck("""
          def hello: Unit = ()
          val io = Sync.ensure(hello)(1)""")

        assert(result == Result.succeed(()))
    }
end Issue1232
