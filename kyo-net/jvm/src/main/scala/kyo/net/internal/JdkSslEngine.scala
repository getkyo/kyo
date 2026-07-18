package kyo.net.internal

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import kyo.*
import kyo.ffi.Buffer

/** The JVM SSLEngine [[TlsEngine]] fallback (priority-10 `jdk`), selected when BoringSSL is not available or `-Dkyo.net.tls=jdk` is forced.
  *
  * This re-expresses the existing `NioTransport` SSLEngine wrap/unwrap logic (`NioTlsState` + `driveHandshake` + `writeTls` +
  * `tryUnwrapBuffered`) on the [[TlsEngine]] feed/drain surface. No new TLS logic is invented: each method maps 1:1 to an `SSLEngine.wrap` /
  * `SSLEngine.unwrap` call. The four JSSE buffers move inside the engine:
  *   - `netIn` accumulates inbound ciphertext fed via [[feedCiphertext]] (the old `NioTlsState.netInBuf`);
  *   - `netOut` collects outbound ciphertext that [[drainCiphertext]] copies to the driver (the old `netOutBuf`);
  *   - `appIn` receives decrypted plaintext from `unwrap` (the old `appInBuf`), surfaced through [[readPlain]] / [[readBuffered]].
  *
  * Buffers are kept in read mode (flipped, position at the next unread byte) between calls, so [[hasBufferedPlaintext]] is `appIn.hasRemaining`
  * and [[drainCiphertext]] reads straight from `netOut`. [[handshakeStep]] maps the SSLEngine handshake status to the engine return contract:
  * `NEED_WRAP` produces ciphertext and returns `-1` (want-write), `NEED_UNWRAP` consumes buffered ciphertext and returns `0` (want-read) when
  * it underflows, `NEED_TASK` runs the delegated tasks inline and retries, and `FINISHED` / `NOT_HANDSHAKING` returns `1` (done).
  *
  * Concurrency: all engine ops for a connection route through the per-driver `submitEngineOp` FIFO, which drains one op at a time on a
  * single dedicated worker carrier. This guarantees that no two callers (read pump, write pump, handshake) can call `engine.wrap` or
  * `engine.unwrap` concurrently, and that the `netIn` / `netOut` / `appIn` vars are never mutated by two carriers at the same time.
  * [[certSha256]] is read once at handshake completion on that same serialized path and cached by the transport (the leaf cert is fixed for
  * the connection), and [[free]] is enqueued on the FIFO, so neither runs on the caller's carrier against a live engine. The per-instance
  * lock that previously serialized these calls has been removed: the FIFO provides the equivalent guarantee without blocking a carrier.
  * Note: `NioTransport` uses `SSLEngine` directly (via `NioTlsState`), not via this class; its own engine access is on the NIO poll loop and
  * is governed by that transport, outside this engine's FIFO.
  */
final private[net] class JdkSslEngine(engine: SSLEngine) extends TlsEngine:

    private val session   = engine.getSession
    private val packetCap = session.getPacketBufferSize
    private val appCap    = session.getApplicationBufferSize

    // netIn / appIn are held in READ mode (post-flip) between calls; netOut likewise holds undrained ciphertext in read mode.
    private var netIn: ByteBuffer  = emptyRead(packetCap)
    private var netOut: ByteBuffer = emptyRead(packetCap)
    private var appIn: ByteBuffer  = emptyRead(appCap)

    // Reused scratch holding a single inbound record copied out of `netIn` when later records are coalesced behind it (see [[unwrapOneRecord]]).
    // It is intentionally a field, not a method-local: it is reused across `unwrapOneRecord` calls to avoid a per-record allocation on the hot
    // decrypt path, and only grown (reassigned) when a record exceeds its capacity. Single owner: like `netIn`/`netOut`/`appIn`, it is touched
    // ONLY on the per-driver `submitEngineOp` FIFO worker carrier (see the class scaladoc), so the bare `var` carries no cross-carrier hazard.
    private var oneRecord: ByteBuffer = ByteBuffer.allocate(packetCap)

    // The peer leaf-cert SHA-256, captured ONCE when the handshake completes (see captureCertHash, called from handshakeStep on the FIFO worker
    // carrier where the session is finalized). @volatile so certSha256 can return it from any carrier without touching the live engine: the
    // STARTTLS wiring carrier (PosixTransport.wireUpgraded -> installCertHash) reads it after the FIFO carrier wrote it, instead of racing
    // engine.getSession against a session not yet populated/visible there. Absent until the handshake completes (and for a server engine with no
    // client cert).
    @volatile private var cachedCertHash: Maybe[Span[Byte]] = Absent

    private def emptyRead(cap: Int): ByteBuffer =
        val b = ByteBuffer.allocate(cap)
        b.flip() // position=0, limit=0: nothing to read yet
        b
    end emptyRead

    /** Append `src` (a read-mode buffer) into the read-mode accumulator `dst`, growing if needed. Returns the appended buffer in read mode. */
    private def appendRead(dst: ByteBuffer, src: ByteBuffer): ByteBuffer =
        val needed = dst.remaining() + src.remaining()
        val out =
            if needed > dst.capacity() then ByteBuffer.allocate(Math.max(needed, dst.capacity() * 2))
            else dst.duplicate()
        // Build in write mode then flip to read mode.
        out.clear()
        out.put(dst)
        out.put(src)
        out.flip()
        out
    end appendRead

    // Java enums (SSLEngineResult.{HandshakeStatus,Status}) have no Scala CanEqual, so compare by reference identity (`eq`), the same
    // idiom the inline NioTransport handshake uses.
    private def isStatus(res: SSLEngineResult, s: SSLEngineResult.Status): Boolean = res.getStatus eq s

    def handshakeStep()(using AllowUnsafe): Int =
        // A handshake failure (untrusted chain, hostname mismatch, no common protocol version, malformed record) makes the SSLEngine throw an
        // SSLException. Return -2, the engine contract's fatal-failure code, so the driver routes it to onFailed -> Closed (and drains any alert
        // the engine queued), matching the BoringSSL/OpenSSL engines. Without this the raw SSLException escapes as an unhandled Panic, which the
        // public connect/upgrade surface (declared Abort[Closed]) must never produce.
        try
            val r = doHandshakeStep()
            // The handshake just completed on this serialized FIFO carrier (r == 1: FINISHED / NOT_HANDSHAKING), so the session is finalized and
            // safe to read here. Capture the peer cert hash now; certSha256 then serves it from any carrier, fixing the cert-hash-after-STARTTLS
            // race where the wiring carrier read getPeerCertificates against a session not yet populated/visible from there (io_uring/jdk).
            if r == 1 then captureCertHash()
            r
        catch case _: javax.net.ssl.SSLException => -2
    end handshakeStep

    /** Unwrap exactly the FIRST buffered TLS record from `netIn` into `out`, isolating it from any records coalesced behind it.
      *
      * A JSSE `SSLEngine` mis-decrypts a record when later ciphertext sits behind it in the same `unwrap` input buffer: feeding
      * `[ChangeCipherSpec][Finished][application data]` from one recv fails `bad_record_mac` on the TLS 1.3 Finished (the record at the
      * handshake-to-application key change), while a readiness poller that reads the records in separate recvs decrypts cleanly. Capping the
      * buffer's `limit` to one record is NOT enough: the engine is still affected by the trailing bytes in the shared backing array. A
      * completion-based driver (io_uring) coalesces the whole flight into one recv and hits this; an epoll/kqueue poller reads record-by-record
      * and never does.
      *
      * So when one or more records are coalesced behind the first, this copies exactly the first record into a standalone buffer and unwraps THAT,
      * so the engine sees it with nothing behind it (the separate-recv path), then advances `netIn` by the bytes the engine consumed. When nothing
      * trails the first record (one record per buffer, or the last of a batch), it unwraps `netIn` in place. A partial header (fewer than 5 bytes)
      * or a partial trailing record surfaces as the usual `BUFFER_UNDERFLOW` want-more.
      */
    private def unwrapOneRecord(out: ByteBuffer): SSLEngineResult =
        val p = netIn.position()
        if netIn.remaining() < 5 then engine.unwrap(netIn, out) // partial header: let the engine ask for more ciphertext
        else
            val recLen = 5 + (((netIn.get(p + 3) & 0xff) << 8) | (netIn.get(p + 4) & 0xff))
            if recLen >= netIn.remaining() then
                // Only, last, or partial record: nothing is coalesced behind it, so the engine already sees no trailing ciphertext.
                engine.unwrap(netIn, out)
            else
                // Records are coalesced behind this one: copy just this record into a standalone buffer so the engine sees no trailing bytes, then
                // advance netIn by what it consumed (0 on BUFFER_OVERFLOW, the whole record on a clean decode).
                if oneRecord.capacity() < recLen then oneRecord = ByteBuffer.allocate(recLen)
                oneRecord.clear()
                val src = netIn.duplicate()
                discard(src.limit(p + recLen))
                oneRecord.put(src)
                oneRecord.flip()
                val res = engine.unwrap(oneRecord, out)
                discard(netIn.position(p + res.bytesConsumed()))
                res
            end if
        end if
    end unwrapOneRecord

    private def doHandshakeStep()(using AllowUnsafe): Int =
        val hs = engine.getHandshakeStatus
        if hs eq SSLEngineResult.HandshakeStatus.NEED_WRAP then
            val tmp = ByteBuffer.allocate(packetCap)
            val res = engine.wrap(ByteBuffer.allocate(0), tmp)
            tmp.flip()
            if tmp.hasRemaining then netOut = appendRead(netOut, tmp)
            if isStatus(res, SSLEngineResult.Status.CLOSED) then -2
            else -1 // produced ciphertext: drain it (want-write)
        else if (hs eq SSLEngineResult.HandshakeStatus.NEED_UNWRAP) || (hs eq SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN) then
            if !netIn.hasRemaining then 0 // no buffered ciphertext: want-read
            else
                val out = ByteBuffer.allocate(appCap)
                val res = unwrapOneRecord(out)
                out.flip()
                if out.hasRemaining then appIn = appendRead(appIn, out)
                if isStatus(res, SSLEngineResult.Status.BUFFER_UNDERFLOW) then 0 // need more ciphertext
                else if isStatus(res, SSLEngineResult.Status.CLOSED) then -2
                else if isStatus(res, SSLEngineResult.Status.BUFFER_OVERFLOW) then -2 // appCap should preclude this
                else doHandshakeStep() // OK: the unwrap may have transitioned the handshake, re-evaluate
                end if
            end if
        else if hs eq SSLEngineResult.HandshakeStatus.NEED_TASK then
            var task = engine.getDelegatedTask
            while task != null do
                task.run()
                task = engine.getDelegatedTask
            doHandshakeStep()
        else 1 // FINISHED or NOT_HANDSHAKING
        end if
    end doHandshakeStep

    def feedCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        if len <= 0 then 0
        else
            val arr = Buffer.copyToArray[Byte](buf, 0, len)
            netIn = appendRead(netIn, ByteBuffer.wrap(arr))
            len
    end feedCiphertext

    def drainCiphertext(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        if len <= 0 || !netOut.hasRemaining then 0
        else
            val n   = Math.min(len, netOut.remaining())
            val arr = new Array[Byte](n)
            netOut.get(arr)
            var i = 0
            while i < n do
                buf.set(i, arr(i))
                i += 1
            // Keep netOut compacted in read mode for the next drain.
            netOut = readModeRemainder(netOut)
            n
    end drainCiphertext

    /** Capture the unread tail of a read-mode buffer as a fresh read-mode buffer (drops the already-consumed prefix). */
    private def readModeRemainder(b: ByteBuffer): ByteBuffer =
        val out = ByteBuffer.allocate(Math.max(b.capacity(), 1))
        out.clear()
        out.put(b)
        out.flip()
        out
    end readModeRemainder

    def readPlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        if len <= 0 then 0
        else
            // First serve any already-decrypted plaintext.
            if appIn.hasRemaining then copyAppIn(buf, len)
            else
                // Decrypt from buffered ciphertext. A single unwrap processes exactly one TLS record, and a post-handshake record
                // (NewSessionTicket, KeyUpdate) consumes input while producing ZERO application bytes. When such a record arrives
                // COALESCED ahead of an application record in one recv (epoll under load delivers the server's session-ticket flight and
                // the response in a single read: cipherIn=1251 plainOut=0 in the freeze trace), stopping at the first zero-output unwrap
                // would strand the application record in netIn forever, deadlocking the connection. So loop: keep unwrapping while the
                // engine makes progress (consumes bytes) but yields no application data, and only stop on real want-read (underflow / no
                // ciphertext), a fatal status, or once application bytes have been decrypted.
                @scala.annotation.tailrec
                def loop(): Int =
                    if appIn.hasRemaining then copyAppIn(buf, len)
                    else if engine.isInboundDone then -3 // peer's close_notify already consumed: clean close
                    else if !netIn.hasRemaining then 0   // want-read: no buffered ciphertext
                    else
                        val out = ByteBuffer.allocate(Math.max(appCap, len))
                        val res = unwrapOneRecord(out)
                        out.flip()
                        if out.hasRemaining then appIn = appendRead(appIn, out)
                        if isStatus(res, SSLEngineResult.Status.BUFFER_OVERFLOW) then -2
                        else if appIn.hasRemaining then copyAppIn(buf, len)
                        else if isStatus(res, SSLEngineResult.Status.CLOSED) then -3          // peer sent close_notify: orderly close
                        else if isStatus(res, SSLEngineResult.Status.BUFFER_UNDERFLOW) then 0 // partial record: want more ciphertext
                        else if res.bytesConsumed() > 0 then loop() // consumed a non-app record (e.g. NewSessionTicket); keep draining
                        else 0                                      // OK with no progress: want-read
                        end if
                    end if
                end loop
                loop()
            end if
    end readPlain

    private def copyAppIn(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        val n   = Math.min(len, appIn.remaining())
        val arr = new Array[Byte](n)
        appIn.get(arr)
        var i = 0
        while i < n do
            buf.set(i, arr(i))
            i += 1
        appIn = readModeRemainder(appIn)
        n
    end copyAppIn

    def writePlain(buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int =
        if len <= 0 then 0
        else
            val arr = Buffer.copyToArray[Byte](buf, 0, len)
            val src = ByteBuffer.wrap(arr)
            val tmp = ByteBuffer.allocate(packetCap + len)
            val res = engine.wrap(src, tmp)
            tmp.flip()
            if tmp.hasRemaining then netOut = appendRead(netOut, tmp)
            if isStatus(res, SSLEngineResult.Status.CLOSED) || isStatus(res, SSLEngineResult.Status.BUFFER_OVERFLOW) then -2
            else src.position() // bytes consumed
    end writePlain

    def hasBufferedPlaintext(using AllowUnsafe): Boolean = appIn.hasRemaining

    def readBuffered()(using AllowUnsafe): Span[Byte] =
        if !appIn.hasRemaining then Span.empty[Byte]
        else
            val arr = new Array[Byte](appIn.remaining())
            appIn.get(arr)
            appIn = readModeRemainder(appIn)
            Span.fromUnsafe(arr)
    end readBuffered

    /** The peer leaf-cert SHA-256 (RFC 5929 tls-server-end-point), captured at handshake completion (see [[cachedCertHash]] / captureCertHash).
      * Returns the captured value WITHOUT touching the live engine, so it is safe to call from any carrier. Absent before the handshake completes
      * and for a server engine with no client certificate.
      */
    def certSha256()(using AllowUnsafe): Maybe[Span[Byte]] = cachedCertHash

    /** Compute the peer leaf-cert SHA-256 from the now-finalized session and cache it. Called only from [[handshakeStep]] at handshake completion,
      * on the per-driver FIFO worker carrier where the session is populated and engine access is serialized, so the read cannot race the
      * read/write engine ops. Idempotent: a non-empty hash is computed at most once; a server engine with no peer cert stays Absent.
      */
    private def captureCertHash()(using AllowUnsafe): Unit =
        if cachedCertHash.isEmpty then
            cachedCertHash =
                try
                    val certs = engine.getSession.getPeerCertificates
                    if certs == null || certs.isEmpty then Absent
                    else
                        val leafDer = certs(0).getEncoded
                        val digest  = java.security.MessageDigest.getInstance("SHA-256")
                        Present(Span.fromUnsafe(digest.digest(leafDer)))
                    end if
                catch case _: Throwable => Absent
    end captureCertHash

    def shutdownStep()(using AllowUnsafe): Int =
        // Mark the outbound side closed, then wrap to emit this side's close_notify into netOut for the driver to drain and flush. wrap on a
        // closeOutbound'd engine produces the close_notify record (the SSLEngine analog of the BoringSSL/OpenSSL SSL_shutdown step). One step
        // is enough: this emits our close_notify and does not block waiting for the peer's (one-directional close, RFC 8446 6.1).
        try
            engine.closeOutbound()
            val tmp = ByteBuffer.allocate(packetCap)
            val res = engine.wrap(ByteBuffer.allocate(0), tmp)
            tmp.flip()
            if tmp.hasRemaining then netOut = appendRead(netOut, tmp)
            if isStatus(res, SSLEngineResult.Status.CLOSED) && engine.isOutboundDone && engine.isInboundDone then 1
            else 0 // our close_notify emitted; not waiting for the peer's
        catch case _: Throwable => -2
    end shutdownStep

    def free()(using AllowUnsafe): Unit =
        try engine.closeOutbound()
        catch case _: Throwable => ()

end JdkSslEngine
