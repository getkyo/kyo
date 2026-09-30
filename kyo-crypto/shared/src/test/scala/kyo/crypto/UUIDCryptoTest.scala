package kyo.crypto

import java.nio.charset.StandardCharsets
import kyo.*

class UUIDCryptoTest extends kyo.test.Test[Any]:

    private val dns = parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    private def parse(value: String): UUID =
        UUID.parse(value).getOrThrow

    private def utf8(value: String): Span[Byte] =
        Span.from(value.getBytes(StandardCharsets.UTF_8))

    "v5" - {
        "matches the RFC version-5 DNS namespace vector" in {
            assert(UUID.v5(dns, utf8("www.example.com")).show == "2ed6657d-e927-568b-95e1-2665a8aea6a2")
        }

        "sets the version-5 nibble" in {
            assert(UUID.v5(dns, utf8("www.example.com")).version == 5)
        }

        "sets the RFC variant on version-5 values" in {
            assert(UUID.v5(dns, utf8("www.example.com")).variant == UUID.Variant.RFC)
        }

        "is the truncated SHA-1 of the namespace bytes followed by the name" in {
            val digest = Sha1.hashAll(Chunk(dns.bytes, utf8("www.example.com"))).toArray
            digest(6) = ((digest(6) & 0x0f) | 0x50).toByte
            digest(8) = ((digest(8) & 0x3f) | 0x80).toByte
            assert(UUID.v5(dns, utf8("www.example.com")).bytes.toArray.toSeq == digest.take(16).toSeq)
        }

        "changes version-5 output when the namespace bytes change" in {
            val otherNamespace = parse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
            assert(UUID.v5(dns, utf8("www.example.com")) != UUID.v5(otherNamespace, utf8("www.example.com")))
        }

        "changes version-5 output when the exact name bytes change" in {
            assert(UUID.v5(dns, utf8("www.example.com")) != UUID.v5(dns, utf8("www.example.coM")))
        }

        "accepts an empty name" in {
            assert(UUID.v5(dns, Span.empty[Byte]).version == 5)
            assert(UUID.v5(dns, Span.empty[Byte]) != UUID.v5(dns, utf8("a")))
        }
    }

    "v8Sha256" - {
        "matches the Kyo version-8 SHA-256 profile snapshot" in {
            assert(UUID.v8Sha256(dns, utf8("kyo")).show == "40b14c55-e8a6-81ec-befd-7dcf39275a9b")
        }

        "encodes the largest Span length as an unsigned 32-bit value" in {
            assert(UUIDCrypto.unsignedIntBytes(Int.MaxValue).toArray.toSeq == Seq[Byte](0x7f, 0xff.toByte, 0xff.toByte, 0xff.toByte))
            assert(UUIDCrypto.unsignedIntBytes(21).toArray.toSeq == Seq[Byte](0, 0, 0, 21))
        }

        "sets the version-8 nibble" in {
            assert(UUID.v8Sha256(dns, utf8("kyo")).version == 8)
        }

        "sets the RFC variant on version-8 values" in {
            assert(UUID.v8Sha256(dns, utf8("kyo")).variant == UUID.Variant.RFC)
        }

        "returns equal version-8 values for equal inputs" in {
            assert(UUID.v8Sha256(dns, utf8("kyo")) == UUID.v8Sha256(dns, utf8("kyo")))
        }

        "changes version-8 output when the namespace bytes change" in {
            val otherNamespace = parse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
            assert(UUID.v8Sha256(dns, utf8("kyo")) != UUID.v8Sha256(otherNamespace, utf8("kyo")))
        }

        "changes version-8 output when the exact name bytes change" in {
            assert(UUID.v8Sha256(dns, utf8("kyo")) != UUID.v8Sha256(dns, utf8("Kyo")))
        }

        "length-prefixes the name so a shifted boundary is a different input" in {
            assert(UUID.v8Sha256(dns, utf8("ab")) != UUID.v8Sha256(dns, utf8("a")))
            assert(UUID.v8Sha256(dns, Span.empty[Byte]).version == 8)
        }
    }

end UUIDCryptoTest
