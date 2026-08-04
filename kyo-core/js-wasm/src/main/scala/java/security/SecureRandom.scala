package java.security

/** `java.security.SecureRandom` for the Scala.js and WebAssembly platforms, which ship no implementation of their own.
  *
  * This class exists purely for linkage: `java.util.UUID.randomUUID` resolves its entropy through a `java.security.SecureRandom` singleton,
  * so the Scala.js linker rejects the whole program when the class is missing, even a program that never names it. No kyo code routes through
  * this shim; kyo's own entropy path is [[kyo.SecureRandom]] and never touches a JDK type. The shim delegates to that public capability so
  * there is one entropy implementation per platform rather than two. `next` is overridden because the inherited one draws from the seeded
  * generator of the superclass rather than a secure source, and `setSeed` is ignored because a caller cannot make this generator
  * reproducible.
  */
class SecureRandom extends java.util.Random(0L):

    override def nextBytes(bytes: Array[Byte]): Unit =
        import kyo.AllowUnsafe.embrace.danger
        val span = kyo.SecureRandom.live.unsafe.nextBytes(bytes.length)
        var i    = 0
        while i < bytes.length do
            bytes(i) = span(i)
            i += 1
    end nextBytes

    override protected def next(bits: Int): Int =
        val bytes = new Array[Byte](4)
        nextBytes(bytes)
        val n = ((bytes(0) & 0xff) << 24) |
            ((bytes(1) & 0xff) << 16) |
            ((bytes(2) & 0xff) << 8) |
            (bytes(3) & 0xff)
        n >>> (32 - bits)
    end next

    override def setSeed(seed: Long): Unit = ()
end SecureRandom
