package kyo.ffi.it

import kyo.*
import kyo.AllowUnsafe

abstract class Test extends kyo.test.Test[Any]:

    // Unsafe: the FFI integration test suites exercise Buffer / Ffi.load directly at the test boundary,
    // where embrace.danger is the sanctioned proof for the unsafe tier.
    protected given AllowUnsafe = AllowUnsafe.embrace.danger

    override def timeout = Duration.fromJava(java.time.Duration.ofSeconds(15))

end Test
