package kyo.net.internal

import kyo.*

/** The hash RFC 5929 section 4.1 selects for the tls-server-end-point channel binding of a certificate: the hash of the certificate's own
  * signature algorithm, or SHA-256 when that hash is MD5 or SHA-1. A server computes its binding by this rule, so a client that always sends
  * SHA-256 is refused for a certificate signed with SHA-384 or SHA-512.
  *
  * The native engines select the same hash through OpenSSL's `OBJ_find_sigid_algs` in `kyo_ssl_common.h`. This is the selection for the
  * engines that only see the DER (the JDK `SSLEngine` and Node's `tls`), read from the `signatureAlgorithm` OID that follows `tbsCertificate`
  * in the outer `Certificate` SEQUENCE. A signature algorithm with no single hash (Ed25519, RSASSA-PSS) or one not listed yields `Absent`,
  * as on the native engines, so the caller reports no binding rather than a wrong one.
  */
private[net] object TlsServerEndPoint:

    /** A hash the selection can name, with the name each runtime's digest API takes. */
    enum Hash(val jdkName: String, val nodeName: String) derives CanEqual:
        case Sha224 extends Hash("SHA-224", "sha224")
        case Sha256 extends Hash("SHA-256", "sha256")
        case Sha384 extends Hash("SHA-384", "sha384")
        case Sha512 extends Hash("SHA-512", "sha512")
    end Hash

    // DER contents of each signature OID, with MD5 and SHA-1 already mapped to SHA-256.
    private val byOid: Chunk[(Array[Byte], Hash)] =
        def rsa(last: Int): Array[Byte]   = Array(0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, last).map(_.toByte)
        def ecdsa(last: Int): Array[Byte] = Array(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, last).map(_.toByte)
        def dsa2(last: Int): Array[Byte]  = Array(0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x03, last).map(_.toByte)
        Chunk(
            rsa(0x04)                                                     -> Hash.Sha256, // md5WithRSAEncryption
            rsa(0x05)                                                     -> Hash.Sha256, // sha1WithRSAEncryption
            rsa(0x0e)                                                     -> Hash.Sha224,
            rsa(0x0b)                                                     -> Hash.Sha256,
            rsa(0x0c)                                                     -> Hash.Sha384,
            rsa(0x0d)                                                     -> Hash.Sha512,
            Array(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x01).map(_.toByte) -> Hash.Sha256, // ecdsa-with-SHA1
            ecdsa(0x01)                                                   -> Hash.Sha224,
            ecdsa(0x02)                                                   -> Hash.Sha256,
            ecdsa(0x03)                                                   -> Hash.Sha384,
            ecdsa(0x04)                                                   -> Hash.Sha512,
            Array(0x2a, 0x86, 0x48, 0xce, 0x38, 0x04, 0x03).map(_.toByte) -> Hash.Sha256, // dsa-with-SHA1
            dsa2(0x01)                                                    -> Hash.Sha224,
            dsa2(0x02)                                                    -> Hash.Sha256
        )
    end byOid

    /** The hash for the certificate `der`, or `Absent` when its signature algorithm has none or the DER is not a certificate. */
    def hashOf(der: Array[Byte]): Maybe[Hash] =
        // Certificate ::= SEQUENCE { tbsCertificate SEQUENCE, signatureAlgorithm SEQUENCE { OID, params }, signature BIT STRING }
        header(der, 0, 0x30).flatMap { (certBody, _) =>
            header(der, certBody, 0x30).flatMap { (tbsBody, tbsLength) =>
                header(der, tbsBody + tbsLength, 0x30).flatMap { (algBody, _) =>
                    header(der, algBody, 0x06).flatMap { (oidBody, oidLength) =>
                        val oid = java.util.Arrays.copyOfRange(der, oidBody, oidBody + oidLength)
                        Maybe.fromOption(byOid.find((known, _) => java.util.Arrays.equals(known, oid)).map(_._2))
                    }
                }
            }
        }

    /** The (content offset, content length) of the DER element at `at` when its tag is `tag`. */
    private def header(der: Array[Byte], at: Int, tag: Int): Maybe[(Int, Int)] =
        if at + 1 >= der.length || (der(at) & 0xff) != tag then Absent
        else
            val first = der(at + 1) & 0xff
            if first < 0x80 then within(der, at + 2, first)
            else
                val count = first & 0x7f
                if count == 0 || count > 3 || at + 2 + count > der.length then Absent
                else
                    val length = (0 until count).foldLeft(0)((acc, i) => (acc << 8) | (der(at + 2 + i) & 0xff))
                    within(der, at + 2 + count, length)
                end if
            end if
    end header

    private def within(der: Array[Byte], body: Int, length: Int): Maybe[(Int, Int)] =
        if body + length <= der.length then Present((body, length)) else Absent

end TlsServerEndPoint
