package kyo.internal

import kyo.*

private[kyo] trait UUIDEntropyPlatform:
    def next16(using Frame): Span[Byte] < Sync
end UUIDEntropyPlatform

private[kyo] object UUIDEntropyPlatform extends UUIDEntropyPlatformPlatformSpecific
