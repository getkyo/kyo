package kyo.crypto

import kyo.*

/** [[Pbkdf2]] against Node's `pbkdf2Sync`. */
class Pbkdf2NodeTest extends kyo.test.Test[Any]:

    "PBKDF2-HMAC-SHA-256 matches Node for 40 random passwords, salts, counts and lengths" in {
        val seed   = new java.util.Random().nextLong()
        val random = new java.util.Random(seed)
        (0 until 40).foreach { i =>
            val password = new Array[Byte](random.nextInt(150))
            val salt     = new Array[Byte](random.nextInt(64))
            random.nextBytes(password)
            random.nextBytes(salt)
            val iterations = 1 + random.nextInt(200)
            val length     = 1 + random.nextInt(100)
            val expected   = NodeOracle.pbkdf2Sha256(password, salt, iterations, length)
            Pbkdf2.hmacSha256Array(password, salt, iterations, length) match
                case Result.Success(ours) => assert(ours.sameElements(expected), s"seed $seed, case $i")
                case other                => fail(s"seed $seed, case $i: Node derived a key where this module answered $other")
        }
    }

end Pbkdf2NodeTest
