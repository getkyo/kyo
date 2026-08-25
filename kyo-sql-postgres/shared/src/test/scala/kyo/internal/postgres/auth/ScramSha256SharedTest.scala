package kyo.internal.postgres.auth

import java.nio.charset.StandardCharsets
import java.util.Base64
import kyo.*
import kyo.Span
import kyo.SqlException

/** Unit tests for ScramSha256Shared.
  *
  * RFC 7677 §3 test vectors are used for byte-exact verification.
  *
  * RFC 7677 §3 values:
  *   - username = "user"
  *   - password = "pencil"
  *   - client nonce = "rOprNGfwEbeRWgbNEkqO"
  *   - server nonce suffix = "%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0"
  *   - salt (b64) = "W22ZaJ0SNY7soEsUEjb6gQ=="
  *   - iterations = 4096
  *   - client-first = "n,,n=user,r=rOprNGfwEbeRWgbNEkqO"
  *   - server-first = "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
  *   - client-final = "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ="
  *   - server-final = "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="
  *
  * NOTE on ClientProof: the value above is the RFC's own published ClientProof, byte for byte, and this file confirms it independently
  * twice over:
  *   1. The ServerSignature matching the RFC value exactly (`6rriTRBi23WpRR...`).
  *   2. A real Postgres 16 server accepting the authentication (integration tests pass).
  *
  * The SCRAM-PLUS leaves use a deterministic fixture whose client-final-message and server signature were computed offline with Python's
  * `hashlib`/`hmac` (an implementation independent of this codebase), so they pin the channel-binding path byte-exactly. The `y,,` leaves
  * near the end of the file use a second such fixture.
  *
  * Two properties here are not about the derivation at all. Which of RFC 5802's three GS2 flags goes out decides whether a stripped
  * `-PLUS` is detectable, and the server-chosen iteration count decides how much uninterruptible work an unauthenticated peer can command;
  * [[StartupExchangeTest]] covers the choice of flag, this file covers the bytes each choice produces and the ceiling on the count.
  */
class ScramSha256SharedTest extends kyo.Test:

    // RFC 7677 §3 test vector constants
    private val rfcUsername    = "user"
    private val rfcPassword    = "pencil"
    private val rfcClientNonce = "rOprNGfwEbeRWgbNEkqO"
    private val rfcServerFirst =
        "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
    private val rfcServerNonce        = "rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0"
    private val rfcClientFinalWoProof = s"c=biws,r=$rfcServerNonce"
    // The published RFC 7677 §3 ClientProof, byte for byte, and independently confirmed here by the
    // ServerSignature check and by a real Postgres accepting it.
    private val rfcClientFinal =
        s"c=biws,r=$rfcServerNonce,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ="
    private val rfcServerFinal = "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="

    "ScramSha256Shared clientFirstMessage format" in {
        val scram = ScramSha256Shared("alice", "someNonce123", ChannelBinding.NotSupported)
        val cfm   = scram.clientFirstMessage
        assert(cfm.startsWith("n,,n=alice,r=someNonce123"))
        assert(cfm == "n,,n=alice,r=someNonce123")
    }

    "ScramSha256Shared RFC 7677 test vector client-first" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        assert(scram.clientFirstMessage == s"n,,n=$rfcUsername,r=$rfcClientNonce")
    }

    "ScramSha256Shared RFC 7677 test vector client-proof byte-exact" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err)              => fail(s"clientFinalMessage failed: $err")
            case Result.Success((clientFinal, _)) =>
                // Must match RFC 7677 §3 exactly
                assert(
                    clientFinal == rfcClientFinal,
                    s"Expected:\n  $rfcClientFinal\nGot:\n  $clientFinal"
                )
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared verifyServerSignature accepts valid RFC 7677 vector" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err) => fail(s"clientFinalMessage failed: $err")
            case Result.Success((_, serverSig)) =>
                val result = scram.verifyServerSignature(rfcServerFinal, serverSig)
                assert(result.isSuccess, s"Expected Success but got: $result")
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared verifyServerSignature rejects tampered signature" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err)            => fail(s"clientFinalMessage failed: $err")
            case Result.Success((_, serverSig)) =>
                // Tamper: flip the last byte
                val tampered = serverSig.clone()
                tampered(tampered.length - 1) = (tampered(tampered.length - 1) ^ 0xff).toByte
                val b64Tampered = "v=" + Base64.getEncoder.encodeToString(tampered)
                val result      = scram.verifyServerSignature(b64Tampered, serverSig)
                assert(result.isFailure, "Expected Failure (signature mismatch) but got Success")
                result match
                    case Result.Failure(e: SqlConnectionScramFailedException) =>
                        assert(e.reason.contains("signature mismatch"), s"Expected 'signature mismatch' in: ${e.reason}")
                    case other => fail(s"Expected SqlConnectionScramFailedException, got: $other")
                end match
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared clientFinalMessage includes channelBinding cb=biws" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err) => fail(s"clientFinalMessage failed: $err")
            case Result.Success((clientFinal, _)) =>
                assert(clientFinal.startsWith("c=biws,"), s"Expected 'c=biws,' prefix, got: $clientFinal")
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared clientFinalMessage without-proof excludes p= field" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err)              => fail(s"clientFinalMessage failed: $err")
            case Result.Success((clientFinal, _)) =>
                // The proof is appended last; without-proof = everything before ",p="
                val withoutProof = clientFinal.split(",p=").head
                assert(
                    !withoutProof.contains(",p="),
                    s"client-final-without-proof should not contain 'p=': $withoutProof"
                )
                assert(
                    withoutProof == rfcClientFinalWoProof,
                    s"Expected without-proof:\n  $rfcClientFinalWoProof\nGot:\n  $withoutProof"
                )
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared SaltedPassword PBKDF2 correct iteration count" in {
        // Verify that with the RFC 7677 parameters, PBKDF2 produces the expected SaltedPassword.
        // The full chain is consistent: the ClientProof, verified via server acceptance, implies correct PBKDF2.
        // The expected proof is the RFC's own published value, dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=.
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err)              => fail(s"Unexpected failure: $err")
            case Result.Success((clientFinal, _)) =>
                // Extract proof from client-final
                val proof = clientFinal.split(",p=").lastOption.getOrElse("")
                assert(
                    proof == "dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=",
                    s"PBKDF2 iteration count wrong, proof mismatch: $proof"
                )
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared malformed server-first missing fields raises SqlDecodeException" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage("r=badnonce", rfcPassword) match
            case Result.Failure(e: SqlDecodeScramFormatException) =>
                assert(e.field == "server-first-message", s"expected field 'server-first-message', got: ${e.field}")
                assert(e.text == "r=badnonce", s"expected text 'r=badnonce', got: ${e.text}")
            case Result.Failure(other) =>
                fail(s"Expected SqlDecodeScramFormatException but got: $other")
            case Result.Success(_) =>
                fail("Expected failure for malformed server-first")
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared nonce that does not extend client nonce is rejected" in {
        val scram    = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        val badFirst = "r=TOTALLY_DIFFERENT_NONCE,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
        scram.clientFinalMessage(badFirst, rfcPassword) match
            case Result.Failure(e: SqlConnectionException) =>
                assert(e.message.contains("client nonce"), s"Unexpected error message: ${e.message}")
            case Result.Failure(other) =>
                fail(s"Expected SqlConnectionException but got: $other")
            case Result.Success(_) =>
                fail("Expected failure for non-extending server nonce")
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared encodeNonce produces 32-char base64 for 24-byte Span input" in {
        // ScramSha256Shared.encodeNonce encodes exactly the bytes provided.
        // The StartupExchange generates 24 random bytes (Span[Byte]) and passes to encodeNonce.
        // 24 bytes base64-encoded = 32 characters (24 * 4/3 = 32, no padding needed).
        val twentyFourBytes = Span.from((1 to 24).map(_.toByte).toArray)
        val nonce           = ScramSha256Shared.encodeNonce(twentyFourBytes)
        // base64 of 24 bytes = 32 characters
        assert(nonce.length == 32, s"Expected 32 chars, got ${nonce.length}: $nonce")
        // Nonce must contain only printable ASCII excluding comma (RFC 5802)
        assert(
            nonce.forall(c => c >= '!' && c <= '~' && c != ','),
            s"Nonce contains invalid chars: $nonce"
        )
        // The encoder encodes exactly the bytes it is handed, so a Span of 1..24 base64s to the same text a byte array does
        val expected = java.util.Base64.getEncoder.encodeToString((1 to 24).map(_.toByte).toArray)
        assert(nonce == expected, s"Expected $expected but got $nonce")
    }

    "ScramSha256Shared verifyServerSignature rejects malformed server-final" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err) => fail(s"clientFinalMessage failed: $err")
            case Result.Success((_, serverSig)) =>
                val result = scram.verifyServerSignature("not-valid-format", serverSig)
                assert(result.isFailure)
                result match
                    case Result.Failure(e: SqlDecodeScramFormatException) =>
                        assert(e.field == "server-final-message", s"expected field 'server-final-message', got: ${e.field}")
                        assert(e.text == "not-valid-format", s"expected text 'not-valid-format', got: ${e.text}")
                    case other => fail(s"Expected SqlDecodeScramFormatException, got: $other")
                end match
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256Shared verifyServerSignature handles server-error response" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err) => fail(s"clientFinalMessage failed: $err")
            case Result.Success((_, serverSig)) =>
                val result = scram.verifyServerSignature("e=invalid-proof", serverSig)
                result match
                    case Result.Failure(e: SqlConnectionException) =>
                        assert(e.message.contains("invalid-proof"), s"Expected server error in: ${e.message}")
                    case other => fail(s"Expected SqlConnectionException, got: $other")
                end match
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "SCRAM-PLUS client-final-message and server signature match independently computed RFC 5802 values" in {
        // Deterministic fixture: fixed nonce, username, password, salt, iterations, cert hash.
        // Expected values computed offline with Python (hashlib.pbkdf2_hmac + hmac.new), an implementation
        // independent of this codebase, so this leaf pins the channel-binding path byte-exactly:
        //   saltedPassword  = pbkdf2_hmac('sha256', b"parity_password", bytes([0x55]*16), 4096, 32)
        //   clientKey       = HMAC(saltedPassword, b"Client Key");  storedKey = SHA256(clientKey)
        //   authMessage     = clientFirstBare + "," + serverFirst + "," + clientFinalWithoutProof
        //   clientProof     = clientKey XOR HMAC(storedKey, authMessage)
        //   serverSignature = HMAC(HMAC(saltedPassword, b"Server Key"), authMessage)
        val username    = "parity_user"
        val clientNonce = "deterministic_nonce_1234"
        val password    = "parity_password"
        val certHash    = Span.fill(32)(0xcc.toByte)
        val salt        = Span.fill(16)(0x55.toByte).toArray
        val iterations  = 4096
        // Simulate a server-first-message with a known server nonce, salt, and iteration count.
        val serverNonce = clientNonce + "_server_extension"
        val saltB64     = Base64.getEncoder.encodeToString(salt)
        val serverFirst = s"r=$serverNonce,s=$saltB64,i=$iterations"

        val expectedClientFinal =
            "c=cD10bHMtc2VydmVyLWVuZC1wb2ludCwszMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMzMw=," +
                s"r=$serverNonce,p=d4ZAbhCu/ycBNc2bt5FyPAN/Erex7+jJuOOTAcfzMrA="
        val expectedServerSigB64 = "F/HTzTd7hUe8biS14gXnzh6Wb+C5Zsf6TOuyQ9+fKog="

        val scram = ScramSha256Shared(username, clientNonce, ChannelBinding.Bound(certHash))
        scram.clientFinalMessage(serverFirst, password) match
            case Result.Failure(e) => fail(s"clientFinalMessage failed: $e")
            case Result.Panic(t)   => fail(s"clientFinalMessage panicked: $t")
            case Result.Success((clientFinal, serverSig)) =>
                assert(
                    clientFinal == expectedClientFinal,
                    s"client-final-message does not match the Python-computed value!\nExpected: $expectedClientFinal\nGot:      $clientFinal"
                )
                assert(
                    Base64.getEncoder.encodeToString(serverSig) == expectedServerSigB64,
                    s"server signature does not match the Python-computed value: ${Base64.getEncoder.encodeToString(serverSig)}"
                )
                // The server signature the client derived must also verify the matching server-final-message.
                assert(scram.verifyServerSignature(s"v=$expectedServerSigB64", serverSig).isSuccess)
        end match
    }

    "client-final-message c= attribute base64-decodes to gs2-header || cert-hash" in {
        // Known cert hash: 32 bytes of 0xCC.
        val certHash    = Span.fill(32)(0xcc.toByte)
        val username    = "rfc_user"
        val clientNonce = "rfc_nonce_5678"
        val password    = "rfc_password"
        val salt        = Span.fill(16)(0x77.toByte).toArray
        val iterations  = 4096
        val serverNonce = clientNonce + "_rfc_extension"
        val saltB64     = Base64.getEncoder.encodeToString(salt)
        val serverFirst = s"r=$serverNonce,s=$saltB64,i=$iterations"

        val scram  = ScramSha256Shared(username, clientNonce, ChannelBinding.Bound(certHash))
        val result = scram.clientFinalMessage(serverFirst, password)

        Sync.defer {
            result match
                case Result.Failure(e)                => fail(s"clientFinalMessage failed: $e")
                case Result.Panic(t)                  => fail(s"clientFinalMessage panicked: $t")
                case Result.Success((clientFinal, _)) =>
                    // Extract the c= attribute from the client-final-message.
                    // client-final-message format: "c=<b64>,r=<nonce>,p=<b64>"
                    val cAttr = clientFinal.split(",").find(_.startsWith("c=")).map(_.drop(2)).getOrElse {
                        fail(s"No c= attribute in client-final-message: $clientFinal")
                        ""
                    }
                    assert(cAttr.nonEmpty, s"c= attribute is empty in: $clientFinal")

                    // Base64-decode the c= attribute.
                    val decoded = Base64.getDecoder.decode(cAttr)

                    // Expected gs2-header for SCRAM-PLUS: "p=tls-server-end-point,,"
                    val expectedGs2Header = "p=tls-server-end-point,,".getBytes(StandardCharsets.UTF_8)

                    // Verify prefix == gs2-header bytes.
                    assert(
                        decoded.length >= expectedGs2Header.length,
                        s"decoded c= is shorter (${decoded.length}) than gs2-header (${expectedGs2Header.length})"
                    )
                    val decodedPrefix = decoded.take(expectedGs2Header.length)
                    assert(
                        Span.from(decodedPrefix).constantTimeEquals(Span.from(expectedGs2Header)),
                        s"c= prefix is not gs2-header. Expected 'p=tls-server-end-point,,' bytes, got: '${new String(decodedPrefix)}'"
                    )

                    // Verify suffix == cert hash bytes (32 bytes of 0xCC).
                    val decodedSuffix = decoded.drop(expectedGs2Header.length)
                    assert(
                        decodedSuffix.length == 32,
                        s"cert-hash portion of c= has wrong length: ${decodedSuffix.length}, expected 32"
                    )
                    assert(
                        Span.from(decodedSuffix).constantTimeEquals(certHash),
                        s"cert-hash portion of c= does not match expected hash (32×0xCC)"
                    )
            end match
        }
    }

    // ── The y channel-binding flag (RFC 5802 §6) ──────────────────────────────

    /** Deterministic `y,,` fixture. Expected values computed offline with Python's `hashlib`/`hmac`, an implementation independent of this
      * codebase:
      * {{{
      *   saltedPassword = pbkdf2_hmac('sha256', b"downgrade_password", bytes([0x33]*16), 4096, 32)
      *   authMessage    = clientFirstBare + "," + serverFirst + "," + "c=eSws,r=" + serverNonce
      *   clientProof    = clientKey XOR HMAC(SHA256(clientKey), authMessage)
      * }}}
      */
    private val yUsername    = "downgrade_user"
    private val yClientNonce = "downgrade_nonce_1234"
    private val yPassword    = "downgrade_password"
    private val yServerNonce = s"${yClientNonce}_server_extension"
    private val yServerFirst = s"r=$yServerNonce,s=MzMzMzMzMzMzMzMzMzMzMw==,i=4096"
    private val yClientFinal =
        s"c=eSws,r=$yServerNonce,p=0KHtrUnKxlUs3KP+facRBRcoct/P9fAktbZ/e3Vg2K0="
    private val yServerSigB64 = "PTiOvQSfNzLfyHRTTbBFwAnLsWS/I1UhbIOdJIHxFKs="

    "a client that can bind but was offered no -PLUS mechanism sends y, the flag that makes a stripped -PLUS detectable" in {
        // RFC 5802 §6: a server that supports channel binding MUST fail an exchange whose flag is y, because only an
        // intermediary editing the advertised mechanism list produces that combination. Emitting n instead makes the
        // downgrade indistinguishable from a server that never offered binding, and the exchange then succeeds.
        val scram = ScramSha256Shared(yUsername, yClientNonce, ChannelBinding.SupportedButNotOffered)
        assert(scram.clientFirstMessage == s"y,,n=$yUsername,r=$yClientNonce")
    }

    "the y flag reaches the c= attribute and the signed transcript, byte for byte" in {
        val scram = ScramSha256Shared(yUsername, yClientNonce, ChannelBinding.SupportedButNotOffered)
        scram.clientFinalMessage(yServerFirst, yPassword) match
            case Result.Failure(e)                        => fail(s"clientFinalMessage failed: $e")
            case Result.Panic(t)                          => fail(s"clientFinalMessage panicked: $t")
            case Result.Success((clientFinal, serverSig)) =>
                // base64("y,,") = "eSws", with no binding data appended: y claims capability, not a binding.
                assert(
                    clientFinal == yClientFinal,
                    s"client-final-message does not match the Python-computed value!\nExpected: $yClientFinal\nGot:      $clientFinal"
                )
                assert(Base64.getEncoder.encodeToString(serverSig) == yServerSigB64)
                assert(new String(Base64.getDecoder.decode("eSws"), StandardCharsets.UTF_8) == "y,,")
        end match
    }

    "the y and n flags produce different proofs over the same credentials, so the flag is not cosmetic" in {
        // The gs2 header sits inside c=, which sits inside the auth-message both sides sign. If the two flags produced
        // the same proof the server could not act on the difference, and this leaf would pass while the flag was
        // dropped from the transcript.
        val bound    = ScramSha256Shared(yUsername, yClientNonce, ChannelBinding.SupportedButNotOffered)
        val notBound = ScramSha256Shared(yUsername, yClientNonce, ChannelBinding.NotSupported)
        (bound.clientFinalMessage(yServerFirst, yPassword), notBound.clientFinalMessage(yServerFirst, yPassword)) match
            case (Result.Success((yFinal, _)), Result.Success((nFinal, _))) =>
                assert(yFinal == yClientFinal)
                assert(
                    nFinal == s"c=biws,r=$yServerNonce,p=QgGHdYU+9hKsA+l+IllR3JZldD2YTf7jwUliNp8rJYY=",
                    s"the n,, counterpart does not match its independently computed value: $nFinal"
                )
            case other => fail(s"both derivations must succeed: $other")
        end match
    }

    // ── The server-chosen iteration count is bounded (RFC 5802 §5.1 i attribute) ──

    "a server-chosen iteration count above the ceiling is refused instead of performed" in {
        // The count arrives from a peer the client has not authenticated yet, and PBKDF2 consumes it in a loop with no
        // suspension point, so an accepted count is carrier time no caller-side timeout can reclaim.
        val scram   = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        val hostile = ScramSha256Base.MaxIterations.toLong + 1
        scram.clientFinalMessage(s"r=$rfcServerNonce,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=$hostile", rfcPassword) match
            case Result.Failure(e: SqlConnectionScramIterationsTooHighException) =>
                assert(e.iterations == hostile, s"the refusal must name the count the server asked for, got ${e.iterations}")
                assert(e.limit == ScramSha256Base.MaxIterations, s"the refusal must name the ceiling, got ${e.limit}")
            case other =>
                fail(s"expected SqlConnectionScramIterationsTooHighException, got: $other")
        end match
    }

    "a count above Int.MaxValue is refused for being too high, not misread as a malformed field" in {
        // 2^40 does not fit an Int. Parsing it into one and reporting a format error would hide the largest hostile
        // values behind the message for a typo, and reporting a truncated number would be worse.
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(s"r=$rfcServerNonce,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=1099511627776", rfcPassword) match
            case Result.Failure(e: SqlConnectionScramIterationsTooHighException) =>
                assert(e.iterations == 1099511627776L, s"expected the full count, got ${e.iterations}")
                assert(e.limit == ScramSha256Base.MaxIterations)
            case other =>
                fail(s"expected SqlConnectionScramIterationsTooHighException, got: $other")
        end match
    }

    "the ceiling itself is accepted, so the boundary is not off by one" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(
            s"r=$rfcServerNonce,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=${ScramSha256Base.MaxIterations}",
            rfcPassword
        ) match
            case Result.Success((clientFinal, _)) =>
                assert(clientFinal.startsWith(s"c=biws,r=$rfcServerNonce,p="))
            case other =>
                fail(s"i == MaxIterations must be accepted, got: $other")
        end match
    }

    "a non-numeric iteration count is still a format error, naming the field and the text" in {
        val scram = ScramSha256Shared(rfcUsername, rfcClientNonce, ChannelBinding.NotSupported)
        scram.clientFinalMessage(s"r=$rfcServerNonce,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=lots", rfcPassword) match
            case Result.Failure(e: SqlDecodeScramFormatException) =>
                assert(e.field == "i", s"expected field 'i', got '${e.field}'")
                assert(e.text == "lots", s"expected text 'lots', got '${e.text}'")
            case other =>
                fail(s"expected SqlDecodeScramFormatException, got: $other")
        end match
    }

end ScramSha256SharedTest
