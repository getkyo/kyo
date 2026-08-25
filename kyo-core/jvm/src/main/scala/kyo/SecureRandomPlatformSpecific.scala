package kyo

private[kyo] trait SecureRandomPlatformSpecific:

    /** The JVM's own `java.security.SecureRandom` provider is this platform's secure source.
      *
      * The provider is resolved lazily so that constructing the generator does not touch the JDK provider chain until the first draw. The
      * JDK type is spelled fully qualified because `kyo.SecureRandom` shadows it in this file.
      */
    private[kyo] def liveUnsafe: SecureRandom.Unsafe =
        new SecureRandom.Unsafe:
            private lazy val underlying = new java.security.SecureRandom
            def nextBytes(length: Int)(using AllowUnsafe): Span[Byte] =
                val arr = new Array[Byte](length)
                underlying.nextBytes(arr)
                Span.fromUnsafe(arr)
            end nextBytes
end SecureRandomPlatformSpecific
