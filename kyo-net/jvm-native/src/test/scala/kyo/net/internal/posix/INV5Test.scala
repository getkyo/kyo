package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.Test

/** Read-dispatch-and-close ordering invariant for PosixHandle.
  *
  * INV-5: a close that races an in-flight read-dispatch (or any other guard-holding op) must not free the handle's resources while the op is
  * still using them. The guard on PosixHandle (see PosixHandle.guard, encoded as a holder count + CloseBit + Closed sentinel) enforces this:
  *
  *   - beginDispatch acquires a holder (increments the guard count).
  *   - requestClose, seeing a non-zero count, sets the CloseBit and defers the free to endDispatch.
  *   - endDispatch, seeing the CloseBit and reaching a zero holder count, runs freeResources exactly once.
  *
  * This test drives the guard protocol directly on a PosixHandle constructed with fd=0 (the stdio read end), which produces a valid
  * object without opening real sockets. The guard state machine is exercised through its public/package API:
  * `beginDispatch` / `endDispatch` / `requestClose` / `isClosing`. No real I/O, no real sockets, no threads.
  *
  * The "no use-after-free" property is observed by the guard's Closed sentinel: once freeResources runs, the guard is set to Closed (-1),
  * and any subsequent beginDispatch returns false (cannot acquire) rather than starting a new dispatch against freed resources.
  */
class INV5Test extends Test:

    import AllowUnsafe.embrace.danger

    /** Build a minimal PosixHandle backed by fd 0 (process stdin), which is always open. The handle's guard and fdCloseClaimed are freshly
      * allocated per call; the fd is never written or closed by the handle in these structural tests (freeResources frees the TLS engine and
      * read buffer; it does NOT close the fd, which is the driver's responsibility). Using fd 0 avoids opening a real socket and keeps the
      * test portable.
      */
    private def makeHandle(): PosixHandle =
        PosixHandle.stdio(PosixHandle.DefaultReadBufferSize)

    "INV-5: read-dispatch and close ordering" - {

        // Base case: no dispatch in flight. requestClose frees immediately (guard goes to Closed,
        // the terminal state). A subsequent beginDispatch returns false (no use-after-free).
        "close with no dispatch in flight frees immediately" in {
            val h = makeHandle()
            // No holders: requestClose should free immediately.
            h.requestClose()
            // Guard must be in the Closed terminal state: isClosing returns true.
            assert(h.isClosing(), "handle must be in closing/closed state after requestClose with no holders")
            // A dispatch attempt after close must fail (the guard is in Closed terminal state).
            val acquired = h.beginDispatch()
            assert(!acquired, "beginDispatch must fail after the handle is closed (no use-after-free)")
        }

        // In-flight dispatch: close defers the free to endDispatch. The resources stay valid while
        // the dispatch holds them, and are freed exactly once when endDispatch releases.
        "close while dispatch is in flight defers free to endDispatch" in {
            val h = makeHandle()

            // Begin a dispatch: one holder in flight.
            val acq = h.beginDispatch()
            assert(acq, "beginDispatch must succeed on a fresh handle")

            // Request close while the dispatch is in flight: must NOT free immediately (one holder).
            h.requestClose()

            // The handle is closing (CloseBit set), but resources must NOT be freed yet (dispatch still holds).
            assert(h.isClosing(), "handle must be in closing state after requestClose during in-flight dispatch")

            // A second beginDispatch while a close is pending must fail (CloseBit is set).
            val acq2 = h.beginDispatch()
            assert(!acq2, "beginDispatch must fail when close is already requested (CloseBit is set)")

            // End the dispatch: this is the last holder while the CloseBit is set, so it frees resources.
            val freed = h.endDispatch()
            assert(freed, "endDispatch must return true when it is the last holder and close was requested (it runs freeResources)")
        }

        // Two holders: the free is deferred until the last holder calls endDispatch.
        "close with two holders in flight defers free to the last endDispatch" in {
            val h = makeHandle()

            val acq1 = h.beginDispatch()
            assert(acq1, "first beginDispatch must succeed")
            val acq2 = h.beginDispatch()
            assert(acq2, "second beginDispatch must succeed (two holders)")

            // Request close: deferred (two holders in flight).
            h.requestClose()
            assert(h.isClosing(), "handle must be closing after requestClose")

            // First endDispatch: still one holder left; resources must NOT be freed yet.
            val freed1 = h.endDispatch()
            assert(!freed1, "first endDispatch must return false (one holder still in flight)")

            // Second endDispatch: zero holders + CloseBit set; resources are freed now.
            val freed2 = h.endDispatch()
            assert(freed2, "second endDispatch must return true (last holder, resources freed)")
        }

        // Idempotent: two requestClose calls must not double-free. After the first close request
        // (which freed immediately with no holders), a second requestClose is a no-op.
        "requestClose is idempotent" in {
            val h = makeHandle()
            h.requestClose()
            assert(h.isClosing())

            // Second close: idempotent, must not throw or double-free.
            h.requestClose()
            assert(h.isClosing(), "still in closing/closed state after second requestClose")
        }

        // A dispatch that completes WITHOUT a close in flight: endDispatch returns false (no free).
        "endDispatch without close does not free resources" in {
            val h   = makeHandle()
            val acq = h.beginDispatch()
            assert(acq)
            val freed = h.endDispatch()
            assert(!freed, "endDispatch without requestClose must return false (no free needed)")
            // Handle is NOT closing: a subsequent beginDispatch must still succeed.
            val acq2 = h.beginDispatch()
            assert(acq2, "handle must still be acquirable after endDispatch without close")
            discard(h.endDispatch())
        }
    }

end INV5Test
