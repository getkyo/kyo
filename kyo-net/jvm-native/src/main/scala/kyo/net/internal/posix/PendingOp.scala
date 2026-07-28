package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.net.internal.transport.ReadOutcome

/** One submitted-but-not-yet-reaped io_uring operation, keyed by a dense `user_data` value.
  *
  * Each variant pins the off-heap memory the kernel owns for the duration of the operation, which is the heart of the UAF invariant: the
  * memory MUST stay alive until the operation's CQE is reaped. A [[Read]] pins the handle's reused `readBuffer`; a [[Write]] pins a
  * per-write `Buffer` that is closed only when its send CQE arrives and additionally carries the payload `offset` of its first byte and the
  * requested send `len`, so a partial send (`res < len`) can re-submit the unsent `[offset + res, offset + len)` tail; a [[TlsWrite]] pins
  * the per-send ciphertext `Buffer` the same way and carries the requested send length so a partial send can be re-submitted; a [[Connect]]
  * carries only the promise to complete (its `sockaddr` is pinned by the handle's `connectTarget`); an [[Accept]] pins the addr/addrlen
  * placeholder buffers that `kyo_uring_prep_accept` requires to stay alive until the single-shot accept CQE is reaped.
  * Each accepted connection uses one SQE and one CQE; the accept loop calls [[IoUringDriver.awaitAccept]] with a fresh promise
  * after each CQE to arm the next connection. The buffers are released via `releaseBuffer` when the CQE is processed.
  *
  * Every variant carries its [[handle]] so [[IoUringDriver.cancel]] can find every in-flight op for a handle, and the per-handle
  * in-flight count can be decremented when the CQE is reaped.
  */
private[net] enum PendingOp(val handle: PosixHandle):

    /** A recv submitted for `promise`. `eintrRetries` is the number of times this read has already been re-submitted because its CQE reaped
      * `-EINTR` (a signal interrupted the recv before any byte was transferred; POSIX recv(2) says to retry). It is carried across re-submissions
      * so the retry is bounded by [[IoUringDriver.maxTransientIoRetries]]: a fresh read starts at 0, and the reap re-submits with an incremented
      * count until the bound, past which the last `-EINTR` falls through to the normal hard-error branch (fail Closed) so an EINTR storm cannot spin.
      * `handshakeOwned` marks a recv armed by the STARTTLS handshake's own ciphertext read (awaitReadCiphertext) rather than the application
      * ReadPump. During the upgrade window the reap routes a non-handshake-owned recv (a stray plaintext-pump recv) through the upgrade handoff, but
      * the handshake's own recv must still reach the engine, so the tag travels with the op (it cannot be inferred from handle flags at reap time).
      * `armedPostUpgrade` snapshots `handle.isUpgraded` at the moment THIS recv was originally armed (not re-read on an EINTR/SQ-full resubmit,
      * which carries the ORIGINAL value forward): a non-`handshakeOwned` recv armed while `false` that reaps once the handle's CURRENT
      * `isUpgraded` is `true` is a stale pre-upgrade pump recv outliving the whole upgrade, indistinguishable from a genuine post-upgrade recv
      * using `handshakeOwned`/`isUpgraded` alone (see the routing condition in `complete`). `armedForStaging` is RECOMPUTED fresh every time
      * `submitRecv` actually runs (unlike `armedPostUpgrade`): it is always exactly "did the SQE this specific CQE reaps for target
      * `recvStagingFor`'s buffer (`true`) or `handle.readBuffer` (`false`)", tautologically consistent with the buffer `submitRecv` chose at
      * that exact moment. Carried so the feed-time buffer-role check (`complete`'s TLS-feed branch) can catch a recv reaping against a buffer
      * its own kernel write never touched -- a permanent, always-on safety net for the buffer-mismatch corruption class, independent of
      * whether the routing condition above correctly identifies every stale-recv trigger.
      */
    case Read(
        promise: Promise.Unsafe[ReadOutcome, Abort[Closed]],
        h: PosixHandle,
        eintrRetries: Int,
        handshakeOwned: Boolean,
        armedPostUpgrade: Boolean,
        armedForStaging: Boolean
    ) extends PendingOp(h)

    /** A plaintext send: pins the per-write `buf` for the kernel for the duration of the send SQE. `offset` is the index of `buf`'s first byte
      * in the original payload span and `len` is the number of bytes this SQE was asked to send, so the reap can detect a partial send
      * (`res < len`) and re-submit the unsent `[offset + res, offset + len)` tail on a fresh per-write buffer (raw sends are held single-in-flight
      * per handle, so the remainder is sent before any later write). The buffer is per-write and IS closed on reap (unlike the TlsWrite mirror).
      */
    case Write(h: PosixHandle, buf: Buffer[Byte], offset: Int, len: Int) extends PendingOp(h)

    /** A TLS ciphertext send: pins the per-handle flush mirror for the kernel for the duration of the send SQE. Carries the requested send `len`
      * so the reap can detect a partial send (`res < len`) and re-submit the unsent ciphertext remainder (io_uring has no writability re-arm).
      * The buffer is the per-handle reused flush mirror; it must NOT be closed on reap (it is freed only in `freeResources`).
      */
    case TlsWrite(h: PosixHandle, buf: Buffer[Byte], len: Int)                 extends PendingOp(h)
    case Connect(promise: Promise.Unsafe[Unit, Abort[Closed]], h: PosixHandle) extends PendingOp(h)
    case Accept(
        promise: Promise.Unsafe[Int, Abort[Closed]],
        h: PosixHandle,
        noAddr: Buffer[Byte],
        noLen: Buffer[Int]
    ) extends PendingOp(h)

    /** Fail the promise this op carries with `closed`. A [[Write]] op carries no promise (its failure is surfaced on the write pump), so
      * this is a no-op for it.
      */
    def failPromise(closed: Closed)(using AllowUnsafe): Unit =
        this match
            case Read(promise, _, _, _, _, _) => promise.completeDiscard(Result.fail(closed))
            case Connect(promise, _)          => promise.completeDiscard(Result.fail(closed))
            case Accept(promise, _, _, _)     => promise.completeDiscard(Result.fail(closed))
            case Write(_, _, _, _)            => ()
            case TlsWrite(_, _, _)            => ()
        end match
    end failPromise

    /** Release the off-heap memory this op pinned for the kernel. Safe to call only after the op's CQE has been reaped.
      *
      * [[Write]] and [[Accept]] own per-op buffers and close them here. [[TlsWrite]] pins the per-handle reused flush mirror (owned by the
      * handle, freed only in `freeResources`); closing it here would be a use-after-free because the next flush refills the same buffer.
      * So [[TlsWrite]] is intentionally a no-op: the mirror survives the reap and is reused by the next flush.
      */
    def releaseBuffer()(using AllowUnsafe): Unit =
        this match
            case Write(_, buf, _, _)     => buf.close()
            case TlsWrite(_, _, _)       => () // per-handle reused mirror; freed only in freeResources, never on reap
            case Accept(_, _, addr, len) => addr.close(); len.close()
            case _                       => ()
end PendingOp
