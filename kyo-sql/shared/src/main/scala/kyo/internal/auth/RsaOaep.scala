package kyo.internal.auth

import kyo.*
import kyo.SqlException
import kyo.internal.Hash

/** Pure-Scala RSA-OAEP encryption (RFC 8017 §7.1.1) using SHA-1 and MGF1-SHA-1.
  *
  * Matches the byte-for-byte output of `javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")` when given the same random
  * seed. Works on JVM, Scala Native, and Scala.js via `scala.math.BigInt.modPow`.
  *
  * Primary entry point: [[RsaOaep.encrypt]].
  */
private[kyo] object RsaOaep:

    /** Platform-neutral RSA public key. Carries the raw big-endian modulus and exponent decoded from a SubjectPublicKeyInfo DER structure.
      *
      * @param modulus
      *   RSA modulus n (big-endian, positive)
      * @param exponent
      *   RSA public exponent e (typically 65537 = 0x010001)
      */
    final case class RsaPublicKey(modulus: BigInt, exponent: BigInt) derives CanEqual

    // SHA-1 output length (hLen) per RFC 8017.
    private val hLen = 20

    // SHA-1 of empty string (lHash for empty label).
    // da39a3ee5e6b4b0d3255bfef95601890afd80709
    private val lHash: Array[Byte] = sha1(Array.empty[Byte])

    /** Encrypts `plaintext` using RSA-OAEP with SHA-1 / MGF1-SHA-1 and an empty label.
      *
      * Equivalent to `javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")` when seeded with the same `random` instance.
      *
      * @param publicKeyPem
      *   PEM-encoded SubjectPublicKeyInfo RSA public key string ("-----BEGIN PUBLIC KEY-----" header)
      * @param plaintext
      *   message to encrypt (must be ≤ k−2·hLen−2 bytes, where k is the key size in bytes)
      * @param random
      *   random generator for the OAEP seed; pass [[kyo.Random.secure]] in production
      * @return
      *   RSA-OAEP ciphertext as a [[Span]] of k bytes
      */
    def encrypt(
        publicKeyPem: String,
        plaintext: Span[Byte],
        random: Random
    )(using Frame): Span[Byte] < (Sync & Abort[SqlException.Request]) =
        parsePem(publicKeyPem).flatMap { key =>
            val k      = keyLenBytes(key.modulus)
            val maxLen = k - 2 * hLen - 2
            val mLen   = plaintext.size
            if mLen > maxLen then
                Abort.fail(SqlException.Request(
                    s"RSA-OAEP: plaintext length $mLen exceeds maximum $maxLen bytes for ${k * 8}-bit key",
                    Maybe.Absent,
                    summon[Frame]
                ))
            else
                random.nextBytes(hLen).map { seedSeq =>
                    val seed = seedSeq.toArray
                    val em   = emeOaepEncode(plaintext.toArray, k, seed)
                    val m    = os2ip(em)
                    val c    = rsaep(key, m)
                    Span.from(i2osp(c, k))
                }
            end if
        }
    end encrypt

    /** Parses a PEM-encoded SubjectPublicKeyInfo RSA public key.
      *
      * Accepts "-----BEGIN PUBLIC KEY-----" header (PKCS#8 SubjectPublicKeyInfo, as returned by MySQL 8.0). Strips
      * header/footer/whitespace, base64-decodes the body, and parses the resulting DER with [[parseDerSpki]].
      *
      * @param pem
      *   PEM string (may contain headers and whitespace)
      * @return
      *   parsed [[RsaPublicKey]]
      * @throws SqlException.Request
      *   if the PEM header is absent or the DER structure is malformed
      */
    def parsePem(pem: String)(using Frame): RsaPublicKey < Abort[SqlException.Request] =
        val header = "-----BEGIN PUBLIC KEY-----"
        val footer = "-----END PUBLIC KEY-----"
        if !pem.contains(header) then
            Abort.fail(SqlException.Request(
                "RSA-OAEP: PEM data does not contain '-----BEGIN PUBLIC KEY-----' header",
                Maybe.Absent,
                summon[Frame]
            ))
        else
            val cleaned = pem
                .replace(header, "")
                .replace(footer, "")
                .replaceAll("\\s+", "")
            Abort.catching[IllegalArgumentException](e =>
                SqlException.Request(
                    s"RSA-OAEP: invalid base64 in PEM data: ${e.getMessage}",
                    Maybe.Absent,
                    summon[Frame]
                )
            ) {
                java.util.Base64.getDecoder.decode(cleaned)
            }.flatMap { derBytes =>
                parseDerSpki(derBytes)
            }
        end if
    end parsePem

    /** Parses a SubjectPublicKeyInfo DER byte array into an [[RsaPublicKey]].
      *
      * Decodes the ASN.1 BER structure:
      * {{{
      * SubjectPublicKeyInfo ::= SEQUENCE {
      *   algorithm AlgorithmIdentifier,
      *   subjectPublicKey BIT STRING
      * }
      * RSAPublicKey ::= SEQUENCE {
      *   modulus INTEGER,
      *   publicExponent INTEGER
      * }
      * }}}
      *
      * @param der
      *   DER-encoded SubjectPublicKeyInfo bytes
      * @return
      *   [[RsaPublicKey]] with modulus and exponent
      * @throws SqlException.Request
      *   if the DER structure is malformed or an unexpected tag is encountered
      */
    def parseDerSpki(der: Array[Byte])(using Frame): RsaPublicKey < Abort[SqlException.Request] =
        val reader = new DerReader(der)
        // outer SEQUENCE
        reader.readTag(0x30).flatMap { _ =>
            reader.readLength().flatMap { _ =>
                // AlgorithmIdentifier SEQUENCE
                reader.readTag(0x30).flatMap { _ =>
                    reader.readLength().flatMap { algoLen =>
                        // skip OID + NULL
                        reader.skip(algoLen).flatMap { _ =>
                            // BIT STRING
                            reader.readTag(0x03).flatMap { _ =>
                                reader.readLength().flatMap { _ =>
                                    // skip unused-bits byte (always 0x00 for RSA)
                                    reader.skip(1).flatMap { _ =>
                                        // inner RSAPublicKey SEQUENCE
                                        reader.readTag(0x30).flatMap { _ =>
                                            reader.readLength().flatMap { _ =>
                                                reader.readInteger().flatMap { modulus =>
                                                    reader.readInteger().map { exponent =>
                                                        RsaPublicKey(modulus, exponent)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end parseDerSpki

    /** MGF1 mask generation function with SHA-1 (RFC 8017 Appendix B.2.1).
      *
      * @param mgfSeed
      *   seed bytes
      * @param maskLen
      *   desired output length in bytes
      * @return
      *   `maskLen` pseudo-random bytes
      */
    def mgf1(mgfSeed: Array[Byte], maskLen: Int): Array[Byte] =
        val hashes     = (maskLen + hLen - 1) / hLen
        val result     = new Array[Byte](hashes * hLen)
        val counterBuf = new Array[Byte](4)
        var counter    = 0
        while counter < hashes do
            counterBuf(0) = ((counter >>> 24) & 0xff).toByte
            counterBuf(1) = ((counter >>> 16) & 0xff).toByte
            counterBuf(2) = ((counter >>> 8) & 0xff).toByte
            counterBuf(3) = (counter & 0xff).toByte
            val combined = new Array[Byte](mgfSeed.length + 4)
            java.lang.System.arraycopy(mgfSeed, 0, combined, 0, mgfSeed.length)
            java.lang.System.arraycopy(counterBuf, 0, combined, mgfSeed.length, 4)
            val hash = sha1(combined)
            java.lang.System.arraycopy(hash, 0, result, counter * hLen, hLen)
            counter += 1
        end while
        java.util.Arrays.copyOf(result, maskLen)
    end mgf1

    // --- Private helpers ---

    /** EME-OAEP-ENCODE per RFC 8017 §7.1.1. Returns a k-byte encoded message EM. */
    private def emeOaepEncode(m: Array[Byte], k: Int, seed: Array[Byte]): Array[Byte] =
        val mLen  = m.length
        val psLen = k - mLen - 2 * hLen - 2
        // DB = lHash || PS || 0x01 || M   (length = k - hLen - 1)
        val db = new Array[Byte](k - hLen - 1)
        java.lang.System.arraycopy(lHash, 0, db, 0, hLen)
        // PS bytes are already zero (Array.fill default)
        db(hLen + psLen) = 0x01.toByte
        java.lang.System.arraycopy(m, 0, db, hLen + psLen + 1, mLen)

        val dbMask     = mgf1(seed, k - hLen - 1)
        val maskedDb   = xorBytes(db, dbMask)
        val seedMask   = mgf1(maskedDb, hLen)
        val maskedSeed = xorBytes(seed, seedMask)

        // EM = 0x00 || maskedSeed || maskedDB
        val em = new Array[Byte](k)
        em(0) = 0x00.toByte
        java.lang.System.arraycopy(maskedSeed, 0, em, 1, hLen)
        java.lang.System.arraycopy(maskedDb, 0, em, 1 + hLen, k - hLen - 1)
        em
    end emeOaepEncode

    /** OS2IP — octet string to non-negative integer (big-endian unsigned). */
    private def os2ip(bytes: Array[Byte]): BigInt = BigInt(1, bytes)

    /** RSA encryption primitive: c = m^e mod n. */
    private def rsaep(key: RsaPublicKey, m: BigInt): BigInt = m.modPow(key.exponent, key.modulus)

    /** I2OSP — integer to octet string of exactly `xLen` bytes (big-endian, zero-padded on the left). */
    private def i2osp(x: BigInt, xLen: Int): Array[Byte] =
        val raw      = x.toByteArray // signed two's-complement, may have leading 0x00
        val stripped = if raw.length > 0 && raw(0) == 0 then java.util.Arrays.copyOfRange(raw, 1, raw.length) else raw
        val result   = new Array[Byte](xLen)
        if stripped.length > xLen then
            bug(s"RSA-OAEP I2OSP: integer too large (${stripped.length} > $xLen)")
        java.lang.System.arraycopy(stripped, 0, result, xLen - stripped.length, stripped.length)
        result
    end i2osp

    /** Returns the key size in bytes (modulus byte length, rounding up to the next whole byte). */
    private[auth] def keyLenBytes(modulus: BigInt): Int = (modulus.bitLength + 7) / 8

    /** XORs two equal-length byte arrays. Returns a fresh array. */
    private def xorBytes(a: Array[Byte], b: Array[Byte]): Array[Byte] =
        val out = new Array[Byte](a.length)
        var i   = 0
        while i < a.length do
            out(i) = (a(i) ^ b(i)).toByte
            i += 1
        end while
        out
    end xorBytes

    /** SHA-1 hash of the input bytes. Delegates to [[Hash.sha1]] which is platform-neutral. */
    private[auth] def sha1(input: Array[Byte]): Array[Byte] =
        Hash.sha1(input)

    // --- DER reader ---

    /** Minimal BER/DER tag-length-value reader for SubjectPublicKeyInfo parsing. Not general-purpose. */
    final private class DerReader(der: Array[Byte]):
        private var pos: Int = 0

        /** Expects the next byte to equal `tag`; advances position. Raises on mismatch. */
        def readTag(tag: Int)(using Frame): Unit < Abort[SqlException.Request] =
            if pos >= der.length then
                Abort.fail(SqlException.Request(
                    s"RSA-OAEP DER: unexpected end of data expecting tag 0x${tag.toHexString}",
                    Maybe.Absent,
                    summon[Frame]
                ))
            else
                val actual = der(pos) & 0xff
                if actual != tag then
                    Abort.fail(SqlException.Request(
                        s"RSA-OAEP DER: expected tag 0x${tag.toHexString} but found 0x${actual.toHexString} at offset $pos",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                else
                    pos += 1
                end if
            end if
        end readTag

        /** Reads a BER length field; returns the length value and advances position past it. */
        def readLength()(using Frame): Int < Abort[SqlException.Request] =
            if pos >= der.length then
                Abort.fail(SqlException.Request("RSA-OAEP DER: unexpected end of data reading length", Maybe.Absent, summon[Frame]))
            else
                val first = der(pos) & 0xff
                pos += 1
                if (first & 0x80) == 0 then
                    // Short form: length is in the 7 lower bits.
                    first
                else
                    val nBytes = first & 0x7f
                    if nBytes == 0 || nBytes > 4 || pos + nBytes > der.length then
                        Abort.fail(SqlException.Request(
                            s"RSA-OAEP DER: unsupported long-form length at offset ${pos - 1}",
                            Maybe.Absent,
                            summon[Frame]
                        ))
                    else
                        var len = 0
                        var i   = 0
                        while i < nBytes do
                            len = (len << 8) | (der(pos) & 0xff)
                            pos += 1
                            i += 1
                        end while
                        len
                    end if
                end if
            end if
        end readLength

        /** Skips `n` bytes unconditionally. */
        def skip(n: Int)(using Frame): Unit < Abort[SqlException.Request] =
            if pos + n > der.length then
                Abort.fail(SqlException.Request(
                    s"RSA-OAEP DER: skip($n) past end of data at offset $pos",
                    Maybe.Absent,
                    summon[Frame]
                ))
            else
                pos += n
            end if
        end skip

        /** Reads a DER INTEGER tag+length+value; returns the value as a positive BigInt. */
        def readInteger()(using Frame): BigInt < Abort[SqlException.Request] =
            readTag(0x02).flatMap { _ =>
                readLength().flatMap { len =>
                    if pos + len > der.length then
                        Abort.fail(SqlException.Request(
                            s"RSA-OAEP DER: INTEGER value exceeds data at offset $pos",
                            Maybe.Absent,
                            summon[Frame]
                        ))
                    else
                        val bytes = java.util.Arrays.copyOfRange(der, pos, pos + len)
                        pos += len
                        // BigInt(1, bytes) interprets as positive regardless of high bit.
                        BigInt(1, bytes)
                    end if
                }
            }
        end readInteger

    end DerReader

end RsaOaep
