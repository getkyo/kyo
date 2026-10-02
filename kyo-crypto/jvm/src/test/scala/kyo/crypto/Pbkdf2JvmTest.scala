package kyo.crypto

import java.nio.charset.StandardCharsets
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kyo.*

/** [[Pbkdf2.hmacSha256]] against the JDK's `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")`. The JDK takes the password as characters
  * and derives from their UTF-8 bytes, so the passwords are text, including ones longer than the 64-byte block.
  */
class Pbkdf2JvmTest extends kyo.test.Test[Any]:

    private def jdk(password: String, salt: Array[Byte], iterations: Int, length: Int): Array[Byte] =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(new PBEKeySpec(password.toCharArray, salt, iterations, length * 8))
            .getEncoded

    "matches the JVM key for 60 random passwords, salts, counts and lengths, across the block and the digest boundaries" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        (0 until 60).foreach { i =>
            val password = new String(Array.fill(1 + random.nextInt(150))(('!' + random.nextInt(90)).toChar))
            val salt     = new Array[Byte](1 + random.nextInt(64))
            random.nextBytes(salt)
            val iterations = 1 + random.nextInt(300)
            val length     = 1 + random.nextInt(100)
            val ours       = Pbkdf2.hmacSha256Array(password.getBytes(StandardCharsets.UTF_8), salt, iterations, length)
            assert(
                ours.map(_.toSeq) == Result.succeed(jdk(password, salt, iterations, length).toSeq),
                s"seed $seed, case $i: ${password.length}-char password, $iterations iterations, $length bytes"
            )
        }
    }

end Pbkdf2JvmTest
