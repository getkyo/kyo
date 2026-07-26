package kyo.internal

import kyo.*

private[kyo] trait UUIDEntropy:
    def next16(using Frame): Span[Byte] < Sync
end UUIDEntropy

private[kyo] object UUIDEntropy extends UUIDEntropyPlatformSpecific
