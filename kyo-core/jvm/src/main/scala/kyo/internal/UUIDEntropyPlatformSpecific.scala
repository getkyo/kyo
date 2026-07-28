package kyo.internal

import java.security.SecureRandom
import kyo.*

private[kyo] trait UUIDEntropyPlatformSpecific:

    val live: UUIDEntropy =
        fromSecureRandomFactory(() => new SecureRandom())

    private[kyo] def fromSecureRandom(source: SecureRandom): UUIDEntropy =
        fromSecureRandomFactory(() => source)

    private[kyo] def fromSecureRandomFactory(factory: () => SecureRandom): UUIDEntropy =
        new UUIDEntropy:
            private lazy val source = factory()

            def next16(using Frame): Span[Byte] < Sync =
                Sync.defer:
                    val bytes = new Array[Byte](16)
                    source.nextBytes(bytes)
                    Span.fromUnsafe(bytes)
end UUIDEntropyPlatformSpecific
