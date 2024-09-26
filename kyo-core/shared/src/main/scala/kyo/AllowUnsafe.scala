package kyo

import kyo.Ansi.*
import kyo.kernel.Safepoint
import scala.annotation.implicitNotFound

@implicitNotFound("""
Unsafe operation detected!
This code requires explicit permission to perform unsafe operations that
break referential transparency.

Recommended approach:
Wrap your unsafe code in IO(...) to properly manage side effects:

IO { /* your unsafe code here */ }

For low-level integration or performance-sensitive code only:
If you must proceed without IO, you have to explicitly embrace the danger:

import AllowUnsafe.embrace.danger // Avoid!

WARNING: Using AllowUnsafe directly bypasses important safety mechanisms.
Ensure you fully understand the risks before proceeding this way.""")
opaque type AllowUnsafe = Null

object AllowUnsafe:
    object embrace:
        inline given danger: AllowUnsafe = null
