package example

import kyo.*

class Issue1232 extends Test:
    "Sync.ensure" in run {
        typeCheck("""
          def hello: Unit = ()
          val io = Sync.ensure(hello)(1)""")
    }
end Issue1232
