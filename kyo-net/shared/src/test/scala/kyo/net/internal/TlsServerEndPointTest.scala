package kyo.net.internal

import kyo.*
import kyo.net.Test
import kyo.net.TlsTestCertShared

class TlsServerEndPointTest extends Test:

    private def der(pem: String): Array[Byte] =
        val body = pem.linesIterator.filterNot(_.startsWith("-----")).mkString
        Base64.decode(body) match
            case Result.Success(bytes) => bytes.toArray
            case other                 => throw new IllegalStateException(s"fixture PEM did not decode: $other")
    end der

    "a sha256WithRSAEncryption certificate selects SHA-256" in {
        assert(TlsServerEndPoint.hashOf(der(TlsTestCertShared.certPem)) == Present(TlsServerEndPoint.Hash.Sha256))
    }

    "an ecdsa-with-SHA384 certificate selects SHA-384" in {
        assert(TlsServerEndPoint.hashOf(der(TlsTestCertShared.ecdsaSha384.certPem)) == Present(TlsServerEndPoint.Hash.Sha384))
    }

    "a sha512WithRSAEncryption certificate selects SHA-512" in {
        assert(TlsServerEndPoint.hashOf(der(TlsTestCertShared.rsaSha512.certPem)) == Present(TlsServerEndPoint.Hash.Sha512))
    }

    "bytes that are not a certificate select nothing" in {
        assert(TlsServerEndPoint.hashOf(Array.emptyByteArray) == Absent)
        assert(TlsServerEndPoint.hashOf(Array[Byte](0x30, 0x03, 0x02, 0x01, 0x00)) == Absent)
    }

    "a truncated certificate selects nothing" in {
        val full = der(TlsTestCertShared.ecdsaSha384.certPem)
        assert(TlsServerEndPoint.hashOf(full.take(full.length / 2)) == Absent)
    }

end TlsServerEndPointTest
