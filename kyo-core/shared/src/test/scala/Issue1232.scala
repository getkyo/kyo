package example

import kyo.*

class Issue1232 extends Test:
    "IO.ensure" in run {
        val result = typeCheck("""
          def hello: Unit = ()
          val io = IO.ensure(hello)(1)""")

        assert(result == Result.succeed(()))
    }
end Issue1232
