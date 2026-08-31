package kyo.proto.kernel.internal

import org.scalatest.freespec.AnyFreeSpec

/** The js-wasm Safepoint's initialization pins.
  *
  * The flag and its enclosing module used to form an initialization cycle: the inline validation
  * lambda was lifted onto `Safepoint$` as a super-argument method, and `Safepoint$`'s constructor
  * reads `period()` through `State.Initial`. A nested object on Scala.js is reached directly
  * rather than through its enclosing module, so a process whose first touch of the flag came from
  * outside entered the cycle mid-construction, built the flag object twice, and the second
  * registration failed the toucher with a duplicate-name error, which is how the first proto suite
  * to construct died with zero events. The pin below is that first touch: it is sharp when this
  * suite runs first in its process, and reads the already-initialized flag otherwise.
  */
class SafepointTest extends AnyFreeSpec:

    "a direct first touch of the period flag initializes it exactly once" in {
        val value = Safepoint.period()
        assert(value == 512)
        val registered = kyo.Flag.get("kyo.proto.kernel.internal.Safepoint.period")
        assert(registered.nonEmpty)
        assert(registered.get eq Safepoint.period)
    }
end SafepointTest
