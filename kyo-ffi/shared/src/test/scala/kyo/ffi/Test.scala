package kyo.ffi

import kyo.AllowUnsafe

abstract class Test extends kyo.test.Test[Any]:
    // Unsafe: the FFI binding layer IS the unsafe tier; suites exercise Buffer / Ffi.load
    // directly at the test boundary, where embrace.danger is the sanctioned proof.
    protected given AllowUnsafe = AllowUnsafe.embrace.danger
end Test
