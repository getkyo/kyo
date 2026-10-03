import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import sbt.*

/** Writes kyo-crypto's `jdk-differential` vector set: what the JDK computes for every primitive the module implements, so Scala Native,
  * which has no JDK crypto to compare against at test time, checks the same answers the JVM suites check live.
  *
  * The set is vendored, not generated per build: the answers are the JDK's at the time of generation, and a later JDK that disagreed would
  * be a finding to read, not a file to overwrite silently. Run `kyo-cryptoJVM/generateJdkVectors` to regenerate; it rewrites `cases.txt`
  * and `MANIFEST` and prints the digest.
  *
  * Every random choice comes from a `java.util.Random` or a `SHA1PRNG` seeded with fixed values, so a rerun under the same JDK writes the
  * same file. One line per case, `kind` then `field=value` pairs, byte values in lowercase hex.
  */
object CryptoDifferentialGen {

    val generateJdkVectors = taskKey[Unit]("Regenerate kyo-crypto's jdk-differential vector set from the running JDK")

    final private case class Line(kind: String, fields: Seq[(String, String)]) {
        def render: String = (kind +: fields.map { case (k, v) => s"$k=$v" }).mkString(" ")
    }

    private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

    private def hexOf(value: BigInteger): String = value.toString(16)

    private def prng(seed: Long): SecureRandom = {
        val random = SecureRandom.getInstance("SHA1PRNG")
        random.setSeed(BigInteger.valueOf(seed).toByteArray)
        random
    }

    /** A `SecureRandom` whose every draw is the pinned seed, so the JDK's OAEP uses the seed kyo is handed. */
    final private class FixedSeed(seed: Array[Byte]) extends SecureRandom {
        override def nextBytes(bytes: Array[Byte]): Unit =
            System.arraycopy(seed, 0, bytes, 0, math.min(seed.length, bytes.length))
    }

    /** The message sizes every digest and MAC case covers: the empty input, the edges of the 64- and 128-byte blocks and of the length
      * field, and a long input.
      */
    private val EdgeSizes = Seq(0, 1, 3, 55, 56, 57, 63, 64, 65, 111, 112, 113, 127, 128, 129, 191, 192, 255, 256, 1000, 4097)

    def write(dir: File, log: Logger): Unit = {
        val random = new java.util.Random(0x6b796f63L)

        def bytes(n: Int): Array[Byte] = { val b = new Array[Byte](n); random.nextBytes(b); b }

        val sizes = EdgeSizes ++ Seq.fill(24)(random.nextInt(600))

        val digests = for {
            (kind, algorithm) <- Seq("sha1" -> "SHA-1", "sha256" -> "SHA-256", "sha512" -> "SHA-512", "md5" -> "MD5")
            size              <- sizes
        } yield {
            val msg = bytes(size)
            Line(kind, Seq("msg" -> hex(msg), "out" -> hex(MessageDigest.getInstance(algorithm).digest(msg))))
        }

        val hmacs = (Seq(0, 1, 31, 32, 63, 64, 65, 128, 200) ++ Seq.fill(8)(random.nextInt(150))).map { keySize =>
            val key = bytes(keySize)
            val msg = bytes(random.nextInt(300))
            val mac = Mac.getInstance("HmacSHA256")
            // An empty key is legal HMAC but SecretKeySpec refuses zero bytes; the JDK's HMAC of an empty key is the HMAC of one zero byte
            // padded to the block, which is the same pad, so the one-zero key stands in for it.
            mac.init(new SecretKeySpec(if (key.isEmpty) Array[Byte](0) else key, "HmacSHA256"))
            Line("hmac", Seq("key" -> hex(key), "msg" -> hex(msg), "out" -> hex(mac.doFinal(msg))))
        }

        val pbkdf2s = Seq((1, 32), (2, 32), (3, 64), (1000, 32), (4096, 20), (7, 33), (1, 1), (10, 100)).map { case (iterations, length) =>
            val password = new String(Array.fill(random.nextInt(80))(('a' + random.nextInt(26)).toChar))
            val salt     = bytes(1 + random.nextInt(40))
            val factory  = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val out      = factory.generateSecret(new PBEKeySpec(password.toCharArray, salt, iterations, length * 8)).getEncoded
            Line(
                "pbkdf2",
                Seq(
                    "password"   -> hex(password.getBytes(StandardCharsets.UTF_8)),
                    "salt"       -> hex(salt),
                    "iterations" -> iterations.toString,
                    "length"     -> length.toString,
                    "out"        -> hex(out)
                )
            )
        }

        val rsaGenerator = KeyPairGenerator.getInstance("RSA")
        rsaGenerator.initialize(2048, prng(1L))
        val rsaPair    = rsaGenerator.generateKeyPair()
        val rsaPublic  = rsaPair.getPublic.asInstanceOf[RSAPublicKey]
        val rsaPrivate = rsaPair.getPrivate.asInstanceOf[RSAPrivateCrtKey]
        val rsaKey     = Line(
            "rsa-key",
            Seq("n" -> hexOf(rsaPublic.getModulus), "e" -> hexOf(rsaPublic.getPublicExponent), "d" -> hexOf(rsaPrivate.getPrivateExponent))
        )

        def jdkVerifiesRsa(n: BigInteger, e: BigInteger, msg: Array[Byte], sig: Array[Byte]): Boolean =
            try {
                val key      = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e))
                val verifier = Signature.getInstance("SHA256withRSA")
                verifier.initVerify(key)
                verifier.update(msg)
                verifier.verify(sig)
            } catch { case _: Exception => false }

        def flip(b: Array[Byte]): Array[Byte] = {
            val copy = b.clone()
            if (copy.nonEmpty) {
                val i = random.nextInt(copy.length * 8)
                copy(i / 8) = (copy(i / 8) ^ (1 << (i % 8))).toByte
            }
            copy
        }

        val rs256s = (0 until 24).flatMap { i =>
            val msg    = bytes(random.nextInt(200))
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(rsaPrivate)
            signer.update(msg)
            val sig = signer.sign()
            val n   = rsaPublic.getModulus
            val e   = rsaPublic.getPublicExponent

            def line(tamper: String, n: BigInteger, msg: Array[Byte], sig: Array[Byte]) =
                Line(
                    "rs256",
                    Seq(
                        "tamper"  -> tamper,
                        "n"       -> hexOf(n),
                        "e"       -> hexOf(e),
                        "msg"     -> hex(msg),
                        "sig"     -> hex(sig),
                        "verdict" -> jdkVerifiesRsa(n, e, msg, sig).toString
                    )
                )
            val flippedN = new BigInteger(1, flip(n.toByteArray.dropWhile(_ == 0)))
            Seq(line("none", n, msg, sig), line("msg", n, flip(msg), sig), line("sig", n, msg, flip(sig)), line("key", flippedN, msg, sig))
        }

        val oaeps = Seq(0, 1, 5, 42, 100, 214).map { size =>
            val msg    = bytes(size)
            val seed   = bytes(20)
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, rsaPublic, new FixedSeed(seed))
            Line("oaep", Seq("seed" -> hex(seed), "msg" -> hex(msg), "out" -> hex(cipher.doFinal(msg))))
        }

        val edGenerator = KeyPairGenerator.getInstance("Ed25519")
        edGenerator.initialize(255, prng(2L))
        def jdkVerifiesEd(pub: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Boolean =
            try {
                // The X.509 SubjectPublicKeyInfo for Ed25519 is a fixed 12-byte prefix followed by the 32-byte encoding.
                val prefix   = Array(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00).map(_.toByte)
                val key      = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(prefix ++ pub))
                val verifier = Signature.getInstance("Ed25519")
                verifier.initVerify(key)
                verifier.update(msg)
                verifier.verify(sig)
            } catch { case _: Exception => false }

        val ed25519s = (0 until 24).flatMap { _ =>
            val pair   = edGenerator.generateKeyPair()
            val pub    = pair.getPublic.getEncoded.takeRight(32)
            val msg    = bytes(random.nextInt(200))
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(pair.getPrivate)
            signer.update(msg)
            val sig = signer.sign()

            def line(tamper: String, pub: Array[Byte], msg: Array[Byte], sig: Array[Byte]) =
                Line(
                    "ed25519",
                    Seq(
                        "tamper"  -> tamper,
                        "pub"     -> hex(pub),
                        "msg"     -> hex(msg),
                        "sig"     -> hex(sig),
                        "verdict" -> jdkVerifiesEd(pub, msg, sig).toString
                    )
                )
            Seq(
                line("none", pub, msg, sig),
                line("msg", pub, flip(msg), sig),
                line("sig", pub, msg, flip(sig)),
                line("key", flip(pub), msg, sig)
            )
        }

        val lines = (digests ++ hmacs ++ pbkdf2s ++ Seq(rsaKey) ++ rs256s ++ oaeps ++ ed25519s).map(_.render)
        val text  = lines.mkString("", "\n", "\n")
        IO.createDirectory(dir)
        IO.write(dir / "cases.txt", text, StandardCharsets.UTF_8)
        val digest  = hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
        val version = System.getProperty("java.vendor") + " " + System.getProperty("java.runtime.version")
        IO.write(
            dir / "MANIFEST",
            s"""source generated by project/CryptoDifferentialGen.scala (kyo-cryptoJVM/generateJdkVectors) with $version
               |license generated from the JDK's answers; no third-party content
               |file cases.txt $digest cases.txt
               |""".stripMargin,
            StandardCharsets.UTF_8
        )
        log.info(s"jdk-differential: ${lines.size} cases, sha-256 $digest, $version")
    }
}
