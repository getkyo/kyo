package kyo.internal

import kyo.*
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js as sjs

class UUIDEntropyPlatformJsWasmTest extends kyo.test.Test[Any]:

    "JavaScript and WebAssembly secure entropy adapter" - {
        "live adapter returns exactly 16 bytes" in {
            UUIDEntropyPlatform.live.next16.map: bytes =>
                assert(bytes.size == 16)
        }

        "fills all 16 bytes through globalThis.crypto.getRandomValues" in {
            var calls        = 0
            var targetLength = -1
            val getRandomValues: sjs.Function1[Int8Array, Int8Array] = target =>
                calls += 1
                targetLength = target.length
                var i = 0
                while i < target.length do
                    target(i) = (i * 11 + 3).toByte
                    i += 1
                target
            val globalThis = sjs.Dynamic.literal(
                crypto = sjs.Dynamic.literal(getRandomValues = getRandomValues)
            )
            val adapter  = UUIDEntropyPlatform.fromGlobalThis(globalThis)
            val expected = Span.from(Array.tabulate[Byte](16)(i => (i * 11 + 3).toByte))

            adapter.next16.map: actual =>
                assert(actual.is(expected))
                assert(calls == 1)
                assert(targetLength == 16)
        }

        "panics when globalThis.crypto is unavailable" in {
            val adapter = UUIDEntropyPlatform.fromGlobalThis(sjs.Dynamic.literal())

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual: IllegalStateException) =>
                        assert(actual.getMessage == "globalThis.crypto.getRandomValues is unavailable")
                    case other => fail(s"expected unavailable Web Crypto panic, got $other")
        }

        "surfaces getRandomValues failures as Sync panics" in {
            val failure                                              = new RuntimeException("getRandomValues failed")
            val getRandomValues: sjs.Function1[Int8Array, Int8Array] = _ => throw failure
            val globalThis = sjs.Dynamic.literal(
                crypto = sjs.Dynamic.literal(getRandomValues = getRandomValues)
            )
            val adapter = UUIDEntropyPlatform.fromGlobalThis(globalThis)

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual) => assert(actual eq failure)
                    case other                => fail(s"expected getRandomValues panic, got $other")
        }
    }

end UUIDEntropyPlatformJsWasmTest
