package kyo.internal

import kyo.*
import scala.scalajs.js
import scala.scalajs.js.typedarray.Int8Array

private[kyo] trait UUIDEntropyPlatformPlatformSpecific:

    val live: UUIDEntropyPlatform =
        fromGlobalThis(js.Dynamic.global.globalThis)

    private[kyo] def fromGlobalThis(globalThis: js.Dynamic): UUIDEntropyPlatform =
        new UUIDEntropyPlatform:
            def next16(using Frame): Span[Byte] < Sync =
                Sync.defer:
                    val crypto = globalThis.selectDynamic("crypto")
                    if js.isUndefined(crypto) || crypto == null then unavailable()
                    val getRandomValues = crypto.selectDynamic("getRandomValues")
                    if js.typeOf(getRandomValues) != "function" then unavailable()
                    val target = new Int8Array(16)
                    discard(crypto.applyDynamic("getRandomValues")(target))
                    val bytes = new Array[Byte](16)
                    var i     = 0
                    while i < 16 do
                        bytes(i) = target(i)
                        i += 1
                    Span.fromUnsafe(bytes)

    private def unavailable(): Nothing =
        throw new IllegalStateException("globalThis.crypto.getRandomValues is unavailable")
end UUIDEntropyPlatformPlatformSpecific
