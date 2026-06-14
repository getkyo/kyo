package kyo.ffi.it

import kyo.*

abstract class Test extends kyo.test.Test[Any]:

    override def timeout = Duration.fromJava(java.time.Duration.ofSeconds(15))

end Test
