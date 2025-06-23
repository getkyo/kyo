package kyo

import scala.annotation.implicitNotFound

@implicitNotFound("""
Use of Kyo's unsafe APIs detected! These are intended for low-level usage like in integrations, libraries, and performance-sensitive code.

Options (in order of preference):

1. Receive an implicit AllowUnsafe parameter
   def myFunction(implicit allow: AllowUnsafe) = // unsafe code here

2. Suspend the operation with Sync
   Sync.Unsafe { // unsafe code here }

3. Import implicit evidence (last resort)
   import AllowUnsafe.embrace.danger
   // unsafe code here

WARNING: Using AllowUnsafe directly bypasses important safety mechanisms and may break referential transparency. Ensure you fully understand the risks before proceeding this way.
""")
opaque type AllowUnsafe = Null

object AllowUnsafe:
    object embrace:
        inline given danger: AllowUnsafe = null
