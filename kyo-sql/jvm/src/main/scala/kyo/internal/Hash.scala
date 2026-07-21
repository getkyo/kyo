package kyo.internal

import java.security.MessageDigest

/** JVM override of [[Hash]] using `java.security.MessageDigest`. The shared/ pure-Scala version is excluded from JVM compile via
  * `build.sbt`'s `excludeFilter`.
  */
private[kyo] object Hash:

    def sha1(input: Array[Byte]): Array[Byte] =
        MessageDigest.getInstance("SHA-1").digest(input)

    def sha256(input: Array[Byte]): Array[Byte] =
        MessageDigest.getInstance("SHA-256").digest(input)

end Hash
