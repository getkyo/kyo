package kyo.internal.crypto

import scala.annotation.tailrec

/** Byte-array equality whose running time does not depend on where, or whether, the contents differ.
  *
  * A verifier that compares a received MAC or signature with `==` or `sameElements` returns at the first mismatching byte, so its timing
  * tells an attacker how long a prefix of a forged tag is correct, and the tag can be recovered a byte at a time. `isEqual` reads every
  * position up to the longer length and folds the differences together before deciding, with no early exit.
  *
  * IMPORTANT: what is guaranteed is the absence of a data-dependent exit in this source. The lengths are not hidden: the loop runs to the
  * longer length, and a length mismatch is a non-match. No guarantee is made about what the JIT, a JavaScript engine or LLVM does with the
  * loop, since none of the four platforms offers a way to pin that down; this is the level of protection a network timing attack on a
  * webhook or interaction endpoint calls for, not protection against a co-located observer measuring cache or power.
  */
private[kyo] object ConstantTime:

    /** Whether `a` and `b` have the same length and the same bytes, inspecting every position regardless of where they differ. */
    def isEqual(a: Array[Byte], b: Array[Byte]): Boolean =
        val length = math.max(a.length, b.length)

        @tailrec def loop(i: Int, diff: Int): Int =
            if i == length then diff
            else
                val x = if i < a.length then a(i) else 0
                val y = if i < b.length then b(i) else 0
                loop(i + 1, diff | (x ^ y))
        loop(0, a.length ^ b.length) == 0
    end isEqual

end ConstantTime
