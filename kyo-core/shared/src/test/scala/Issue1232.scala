package example

import kyo.*

class Issue1232 extends kyo.test.Test[Any]:
    "Sync.ensure" in {
        typeCheck("""
          def hello: Unit = ()
          val io = Sync.ensure(hello)(1)""")
    }
end Issue1232
