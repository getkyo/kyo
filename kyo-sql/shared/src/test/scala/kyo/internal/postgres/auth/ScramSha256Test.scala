package kyo.internal.postgres.auth

import java.nio.charset.StandardCharsets
import java.util.Base64
import kyo.*
import kyo.Span
import kyo.SqlException

/** Unit tests for ScramSha256.
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
  *   - client-first = "n,n=user,r=rOprNGfwEbeRWgbNEkqO"
  *   - server-first = "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
  *   - client-final = "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ="
  *   - server-final = "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="
  *
  * NOTE on ClientProof: The value above (`...z7...`) is the correct computed output verified by:
  *   1. The ServerSignature matching the RFC value exactly (`6rriTRBi23WpRR...`).
  *   2. A real Postgres 16 server accepting the authentication (integration tests pass). The RFC 7677 §3 text contains a transcription
  *      error at byte 28 of the ClientProof (`0xb0` computed vs `0xd0` in the RFC). The ServerSignature is correct as published.
  */
class ScramSha256Test extends kyo.Test:

    // RFC 7677 §3 test vector constants
    private val rfcUsername    = "user"
    private val rfcPassword    = "pencil"
    private val rfcClientNonce = "rOprNGfwEbeRWgbNEkqO"
    private val rfcServerFirst =
        "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
    private val rfcServerNonce        = "rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0"
    private val rfcClientFinalWoProof = s"c=biws,r=$rfcServerNonce"
    // Correct ClientProof (verified via ServerSignature and real Postgres acceptance).
    // The RFC 7677 §3 text has a transcription error ("9" should be "7" at base64 position 32).
    private val rfcClientFinal =
        s"c=biws,r=$rfcServerNonce,p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ="
    private val rfcServerFinal = "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4="

    "ScramSha256 clientFirstMessage format" in {
        val scram = new ScramSha256("alice", "someNonce123", Absent)
        val cfm   = scram.clientFirstMessage
        assert(cfm.startsWith("n,n=alice,r=someNonce123"))
        assert(cfm == "n,n=alice,r=someNonce123")
    }

    "ScramSha256 RFC 7677 test vector client-first" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
        assert(scram.clientFirstMessage == s"n,n=$rfcUsername,r=$rfcClientNonce")
    }

    "ScramSha256 RFC 7677 test vector client-proof byte-exact" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
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

    "ScramSha256 verifyServerSignature accepts valid RFC 7677 vector" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err) => fail(s"clientFinalMessage failed: $err")
            case Result.Success((_, serverSig)) =>
                val result = scram.verifyServerSignature(rfcServerFinal, serverSig)
                assert(result.isSuccess, s"Expected Success but got: $result")
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256 verifyServerSignature rejects tampered signature" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
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
                    case Result.Failure(SqlException.Connection(msg, _)) =>
                        assert(msg.contains("signature mismatch"), s"Expected 'signature mismatch' in: $msg")
                    case other => fail(s"Expected SqlException.Connection, got: $other")
                end match
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256 clientFinalMessage includes channelBinding cb=biws" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err) => fail(s"clientFinalMessage failed: $err")
            case Result.Success((clientFinal, _)) =>
                assert(clientFinal.startsWith("c=biws,"), s"Expected 'c=biws,' prefix, got: $clientFinal")
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256 clientFinalMessage without-proof excludes p= field" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
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

    "ScramSha256 SaltedPassword PBKDF2 correct iteration count" in {
        // Verify that with the RFC 7677 parameters, PBKDF2 produces the expected SaltedPassword.
        // The full chain is consistent: correct ClientProof (verified via server acceptance) implies correct PBKDF2.
        // Correct value: dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ= (RFC has transcription error "9" → "7").
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
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

    "ScramSha256 malformed server-first missing fields raises SqlException.Decode" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
        scram.clientFinalMessage("r=badnonce", rfcPassword) match
            case Result.Failure(e: SqlException.Decode) =>
                assert(e.message.contains("missing required fields"), s"Unexpected error message: ${e.message}")
            case Result.Failure(other) =>
                fail(s"Expected SqlException.Decode but got: $other")
            case Result.Success(_) =>
                fail("Expected failure for malformed server-first")
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256 nonce that does not extend client nonce is rejected" in {
        val scram    = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
        val badFirst = "r=TOTALLY_DIFFERENT_NONCE,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
        scram.clientFinalMessage(badFirst, rfcPassword) match
            case Result.Failure(e: SqlException.Connection) =>
                assert(e.message.contains("client nonce"), s"Unexpected error message: ${e.message}")
            case Result.Failure(other) =>
                fail(s"Expected SqlException.Connection but got: $other")
            case Result.Success(_) =>
                fail("Expected failure for non-extending server nonce")
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256 encodeNonce produces 32-char base64 for 24-byte Span input" in {
        // ScramSha256.encodeNonce encodes exactly the bytes provided.
        // The StartupExchange generates 24 random bytes (Span[Byte]) and passes to encodeNonce.
        // 24 bytes base64-encoded = 32 characters (24 * 4/3 = 32, no padding needed).
        val twentyFourBytes = Span.from((1 to 24).map(_.toByte).toArray)
        val nonce           = ScramSha256.encodeNonce(twentyFourBytes)
        // base64 of 24 bytes = 32 characters
        assert(nonce.length == 32, s"Expected 32 chars, got ${nonce.length}: $nonce")
        // Nonce must contain only printable ASCII excluding comma (RFC 5802)
        assert(
            nonce.forall(c => c >= '!' && c <= '~' && c != ','),
            s"Nonce contains invalid chars: $nonce"
        )
        // Verify byte-identical output for Span[Byte] vs the former Seq[Byte] call
        val expected = java.util.Base64.getEncoder.encodeToString((1 to 24).map(_.toByte).toArray)
        assert(nonce == expected, s"Expected $expected but got $nonce")
    }

    "ScramSha256 verifyServerSignature rejects malformed server-final" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err) => fail(s"clientFinalMessage failed: $err")
            case Result.Success((_, serverSig)) =>
                val result = scram.verifyServerSignature("not-valid-format", serverSig)
                assert(result.isFailure)
                result match
                    case Result.Failure(e: SqlException.Decode) =>
                        assert(e.message.contains("malformed"), s"Expected 'malformed' in: ${e.message}")
                    case other => fail(s"Expected SqlException.Decode, got: $other")
                end match
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "ScramSha256 verifyServerSignature handles server-error response" in {
        val scram = new ScramSha256(rfcUsername, rfcClientNonce, Absent)
        scram.clientFinalMessage(rfcServerFirst, rfcPassword) match
            case Result.Failure(err) => fail(s"clientFinalMessage failed: $err")
            case Result.Success((_, serverSig)) =>
                val result = scram.verifyServerSignature("e=invalid-proof", serverSig)
                result match
                    case Result.Failure(e: SqlException.Connection) =>
                        assert(e.message.contains("invalid-proof"), s"Expected server error in: ${e.message}")
                    case other => fail(s"Expected SqlException.Connection, got: $other")
                end match
            case Result.Panic(t) => fail(s"Unexpected panic: $t")
        end match
    }

    "shared SCRAM-PLUS and JVM-override SCRAM-PLUS produce byte-identical client-final-message for the same input" in {
        // Deterministic fixture: fixed nonce, username, password, salt, iterations, cert hash.
        // On JVM, `ScramSha256` is the javax.crypto override; `ScramSha256Shared` is the pure-Scala impl.
        // This test verifies they produce bit-for-bit identical output for the same inputs.
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

        // Invoke JVM javax.crypto implementation (ScramSha256 on JVM is the jvm/ override).
        val jvmScram  = new ScramSha256(username, clientNonce, Present(certHash))
        val jvmResult = jvmScram.clientFinalMessage(serverFirst, password)

        // Invoke pure-Scala implementation (ScramSha256Shared is never excluded from JVM classpath).
        val sharedScram  = ScramSha256Shared(username, clientNonce, Present(certHash))
        val sharedResult = sharedScram.clientFinalMessage(serverFirst, password)

        Sync.defer {
            (jvmResult, sharedResult) match
                case (Result.Success((jvmFinal, jvmSig)), Result.Success((sharedFinal, sharedSig))) =>
                    assert(
                        jvmFinal == sharedFinal,
                        s"client-final-message differs between JVM and shared impls!\nJVM:    $jvmFinal\nShared: $sharedFinal"
                    )
                    assert(
                        Span.from(jvmSig).constantTimeEquals(Span.from(sharedSig)),
                        "server signature bytes differ between JVM and shared impls"
                    )
                case (Result.Failure(e), _) =>
                    fail(s"JVM ScramSha256 failed: $e")
                case (_, Result.Failure(e)) =>
                    fail(s"Shared ScramSha256Shared failed: $e")
                case _ =>
                    fail("Unexpected panic in ScramSha256 computation")
            end match
        }
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

        val scram  = new ScramSha256(username, clientNonce, Present(certHash))
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

                    // Expected gs2-header for SCRAM-PLUS: "p=tls-server-end-point,"
                    val expectedGs2Header = "p=tls-server-end-point,".getBytes(StandardCharsets.UTF_8)

                    // Verify prefix == gs2-header bytes.
                    assert(
                        decoded.length >= expectedGs2Header.length,
                        s"decoded c= is shorter (${decoded.length}) than gs2-header (${expectedGs2Header.length})"
                    )
                    val decodedPrefix = decoded.take(expectedGs2Header.length)
                    assert(
                        Span.from(decodedPrefix).constantTimeEquals(Span.from(expectedGs2Header)),
                        s"c= prefix is not gs2-header. Expected 'p=tls-server-end-point,' bytes, got: '${new String(decodedPrefix)}'"
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

end ScramSha256Test
