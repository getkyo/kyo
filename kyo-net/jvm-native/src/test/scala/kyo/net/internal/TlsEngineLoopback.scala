package kyo.net.internal

import kyo.*
import kyo.ffi.Buffer

/** In-memory loopback driver for two [[TlsEngine]]s: it shuttles ciphertext between a client and server engine through their feed/drain
  * buffers (no socket), exactly the contract the real driver implements. Used by `TlsEngineTest` to complete a handshake and round-trip
  * plaintext entirely in memory.
  */
object TlsEngineLoopback:

    private val chunk = 16 * 1024 + 512 // a TLS record plus header slack

    /** Drain all ciphertext one engine has queued into a single byte array (empty when nothing pending). */
    def drainAll(engine: TlsEngine)(using AllowUnsafe): Array[Byte] =
        val acc  = new java.io.ByteArrayOutputStream
        var more = true
        while more do
            val buf = Buffer.alloc[Byte](chunk)
            try
                val n = engine.drainCiphertext(buf, chunk)
                if n <= 0 then more = false
                else acc.write(Buffer.copyToArray[Byte](buf, 0, n))
            finally buf.close()
            end try
        end while
        acc.toByteArray
    end drainAll

    /** Feed a full byte array into an engine's read side. */
    def feed(engine: TlsEngine, bytes: Array[Byte])(using AllowUnsafe): Unit =
        if bytes.length > 0 then
            val buf = Buffer.fromArray[Byte](bytes)
            try discard(engine.feedCiphertext(buf, bytes.length))
            finally buf.close()
    end feed

    /** Drive both engines' handshakes to completion by exchanging ciphertext, bounded by `maxRounds` so a stuck handshake fails the test
      * instead of looping forever. Returns true when both sides report done (`handshakeStep == 1`).
      */
    def handshake(client: TlsEngine, server: TlsEngine, maxRounds: Int = 50)(using AllowUnsafe): Boolean =
        var round      = 0
        var clientDone = false
        var serverDone = false
        while round < maxRounds && !(clientDone && serverDone) do
            val cStep = client.handshakeStep()
            if cStep == 1 then clientDone = true
            else if cStep == -2 then return false
            val toServer = drainAll(client)
            feed(server, toServer)

            val sStep = server.handshakeStep()
            if sStep == 1 then serverDone = true
            else if sStep == -2 then return false
            val toClient = drainAll(server)
            feed(client, toClient)
            round += 1
        end while
        clientDone && serverDone
    end handshake

    /** Drain all decrypted plaintext currently buffered in `engine`'s read side, returning the concatenated plaintext bytes (empty when none).
      *
      * Loops `readPlain` until it yields no more bytes (0 or negative), so all complete records fed so far decrypt. Partial records stay buffered
      * in the engine for the next feed. Pair with [[feed]] to decrypt a ciphertext stream incrementally across batches.
      */
    def drainPlain(engine: TlsEngine)(using AllowUnsafe): Array[Byte] =
        val acc  = new java.io.ByteArrayOutputStream
        var more = true
        while more do
            val out = Buffer.alloc[Byte](chunk)
            try
                val n = engine.readPlain(out, chunk)
                if n <= 0 then more = false
                else acc.write(Buffer.copyToArray[Byte](out, 0, n))
            finally out.close()
            end try
        end while
        acc.toByteArray
    end drainPlain

    /** Feed `ciphertext` into `engine`'s read side and drain all decrypted plaintext, returning the concatenated plaintext bytes.
      *
      * Loops `readPlain` until it yields no more bytes (0 or negative), so a ciphertext stream spanning multiple TLS records decrypts fully.
      * Used by the write-path conservation tests to decrypt the bytes the driver sent to the peer socket and compare them to the input
      * plaintext.
      */
    def decrypt(engine: TlsEngine, ciphertext: Array[Byte])(using AllowUnsafe): Array[Byte] =
        feed(engine, ciphertext)
        drainPlain(engine)
    end decrypt

    /** Encrypt `plaintext` through `engine`'s write side and return the produced ciphertext (the bytes a peer would receive on the wire).
      *
      * Calls `writePlain` once with the whole plaintext, then drains all queued ciphertext. Used by the read-race test to produce a REAL TLS
      * ciphertext record the peer pre-sends, so the driver's `feedCiphertext` consumes valid bytes (raw plaintext would make a real engine error).
      */
    def encrypt(engine: TlsEngine, plaintext: Array[Byte])(using AllowUnsafe): Array[Byte] =
        val src = Buffer.fromArray[Byte](plaintext)
        try discard(engine.writePlain(src, plaintext.length))
        finally src.close()
        drainAll(engine)
    end encrypt

    /** Encrypt `plaintext` through `from`, ship the ciphertext to `to`, and decrypt it back, returning the decrypted bytes. */
    def roundTrip(from: TlsEngine, to: TlsEngine, plaintext: Array[Byte])(using AllowUnsafe): Array[Byte] =
        val src = Buffer.fromArray[Byte](plaintext)
        try discard(from.writePlain(src, plaintext.length))
        finally src.close()
        feed(to, drainAll(from))
        val out = Buffer.alloc[Byte](plaintext.length + 512)
        try
            val n = to.readPlain(out, plaintext.length + 512)
            if n > 0 then Buffer.copyToArray[Byte](out, 0, n) else Array.emptyByteArray
        finally out.close()
        end try
    end roundTrip

end TlsEngineLoopback
