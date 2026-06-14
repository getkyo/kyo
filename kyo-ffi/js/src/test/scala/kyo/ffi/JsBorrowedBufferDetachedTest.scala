package kyo.ffi

import kyo.discard
import kyo.internal.BorrowOwner
import kyo.internal.BorrowRevoked
import scala.scalajs.js.JavaScriptException
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js as sjs

/** JS unit spec for detached-`ArrayBuffer` behavior in checked-borrow and unchecked modes.
  *
  * On JS, accessing a detached `ArrayBuffer` (e.g. after `structuredClone(..., { transfer: [...] })` or `ArrayBuffer.prototype.transfer`)
  * causes the JS runtime to throw a `TypeError`, which Scala.js wraps in [[scala.scalajs.js.JavaScriptException]]. This applies regardless
  * of whether the buffer is checked or unchecked.
  *
  * The checked-borrow [[BorrowRevoked]] path only fires when the [[kyo.internal.BorrowOwner]] is explicitly revoked -- it does NOT
  * auto-detect ArrayBuffer detachment.
  */
class JsBorrowedBufferDetachedTest extends Test:

    "checked-borrow mode" - {
        "accesses on a detached backing ArrayBuffer throw JavaScriptException" in {
            val arr = new Uint8Array(8)
            arr(0) = 42
            val borrowOwner = new BorrowOwner("js-detach-owner")
            val buf         = Buffer.Unsafe.wrapBorrowedChecked[Byte](arr, size = 8, borrowOwner)

            // Pre-detach access works.
            assert(buf.get(0) == (42: Byte))

            // Simulate detachment by transferring the underlying ArrayBuffer. Not all runtimes implement
            // `ArrayBuffer.prototype.transfer`; fall back to `structuredClone` with the transfer option and
            // finally to `postMessage`-backed tricks. Node 21+ and modern browsers support `transfer`.
            val hasTransfer    = sjs.typeOf(sjs.Dynamic.global.ArrayBuffer.prototype.transfer) == "function"
            val hasStructClone = sjs.typeOf(sjs.Dynamic.global.structuredClone) == "function"

            if hasTransfer then
                discard(arr.buffer.asInstanceOf[sjs.Dynamic].transfer())
            else if hasStructClone then
                val opts = sjs.Dynamic.literal(transfer = sjs.Array[sjs.Any](arr.buffer.asInstanceOf[sjs.Any]))
                discard(sjs.Dynamic.global.structuredClone(arr.buffer.asInstanceOf[sjs.Any], opts))
            else
                cancel("No ArrayBuffer detachment API available on this JS runtime")
            end if

            // Hoist the byteLength into a plain local before asserting: rendering the detached `arr`/`arr.buffer` in the
            // power-assert diagram would itself call `%TypedArray%.prototype.join` on the detached buffer and throw a
            // TypeError. Comparing only the Int keeps the detached typed array out of the recorded subexpressions.
            val byteLen = arr.buffer.byteLength
            assert(byteLen == 0)
            // The BorrowOwner has not been revoked -- only the underlying ArrayBuffer is detached.
            // The JS runtime throws a TypeError when accessing a detached buffer, which Scala.js
            // wraps in JavaScriptException. BorrowRevoked would only fire if the owner were explicitly revoked.
            val thrown    = intercept[JavaScriptException](buf.get(0))
            val message   = thrown.getMessage
            val mentioned = message.contains("detached")
            assert(mentioned)
        }

        "unchecked wrapBorrowed does NOT probe detachment (no owner attached)" in {
            val arr = new Uint8Array(8)
            arr(0) = 7
            val buf = Buffer.Unsafe.wrapBorrowed[Byte](arr, size = 8)

            // Pre-detach access works.
            assert(buf.get(0) == (7: Byte))

            val hasTransfer    = sjs.typeOf(sjs.Dynamic.global.ArrayBuffer.prototype.transfer) == "function"
            val hasStructClone = sjs.typeOf(sjs.Dynamic.global.structuredClone) == "function"

            if hasTransfer then
                discard(arr.buffer.asInstanceOf[sjs.Dynamic].transfer())
            else if hasStructClone then
                val opts = sjs.Dynamic.literal(transfer = sjs.Array[sjs.Any](arr.buffer.asInstanceOf[sjs.Any]))
                discard(sjs.Dynamic.global.structuredClone(arr.buffer.asInstanceOf[sjs.Any], opts))
            else
                cancel("No ArrayBuffer detachment API available on this JS runtime")
            end if

            // Hoist the byteLength into a plain local before asserting (see the checked-borrow leaf): rendering the
            // detached `arr` in the diagram would call `%TypedArray%.prototype.join` on the detached buffer and throw.
            val byteLen = arr.buffer.byteLength
            assert(byteLen == 0)
            // Post-detach: the F6 BorrowRevoked path must NOT trigger on the unchecked buffer -- the runtime may
            // surface its own error from the typed-view read on a detached ArrayBuffer (Scala.js reports an
            // UndefinedBehaviorError on Node), but the checked-borrow path specifically must not fire since no
            // owner is attached.
            val thrown =
                try
                    discard(buf.get(0))
                    None
                catch case t: Throwable => Some(t)
            thrown match
                case Some(t) =>
                    // Whatever the runtime raised, it must not be the checked-borrow BorrowRevoked (no owner attached).
                    val isRevoked = t.isInstanceOf[BorrowRevoked]
                    assert(!isRevoked)
                case None =>
                    // The unchecked read did not raise at all on this runtime: that is still the required outcome
                    // (no BorrowRevoked from the unchecked path). Assert it explicitly so the leaf never finishes
                    // without reaching an assertion.
                    succeed
            end match
        }
    }
end JsBorrowedBufferDetachedTest
