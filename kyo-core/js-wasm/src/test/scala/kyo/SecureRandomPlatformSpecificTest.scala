package kyo

import kyo.SecureRandom.Candidate
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js as sjs

/** Drives the host conditions the JS and WebAssembly secure source has to survive.
  *
  * A Node version predating the Web Crypto global is not reachable from this build, which is exactly why an unguarded dereference of
  * `globalThis.crypto` survived in an earlier shape of this source. So the condition is created directly: each leaf redefines `crypto` on the
  * global object for the duration of one synchronous block.
  *
  * Removing the global is not enough on its own, because the second candidate then rescues the call, which was measured on both the JS and
  * the WebAssembly host rather than assumed. `js.Dynamic.global.selectDynamic` compiles to a bare global reference, so the `require` probe
  * resolves through the enclosing module scope and no test can take that binding away. That is what `SecureRandom.fillBytesFrom` exists for:
  * restricting the candidate list is how the Web Crypto edge is reached, and the leaves that do it are matched by a control leaf which
  * restricts the same single candidate with the global still in place, so a pass cannot come from the restriction alone.
  *
  * The platform-neutral properties of `SecureRandom` (length, distribution, independence across calls, buffers past the per-call ceiling)
  * live in `kyo.SecureRandomTest` under `shared`, which runs this source through the public capability on both JS and WebAssembly.
  *
  * `scala.scalajs.js` is aliased because `kyo.test.Test` has its own `js` member, the platform selector for a JS-only leaf.
  */
class SecureRandomPlatformSpecificTest extends kyo.test.Test[Any]:

    "the host this suite runs on" - {

        "publishes crypto as a global, so the leaves that remove it are removing something real" in {
            Sync.defer {
                assert(sjs.typeOf(sjs.Dynamic.global.selectDynamic("crypto")) == "object")
            }
        }

        "resolves the crypto module too, so the fallback candidate is covered rather than assumed" in {
            Sync.defer {
                val first  = new Array[Byte](32)
                val second = new Array[Byte](32)
                SecureRandom.fillBytesFrom(Seq(Candidate.CryptoModule), first)
                SecureRandom.fillBytesFrom(Seq(Candidate.CryptoModule), second)
                assert(first.exists(_ != 0.toByte))
                assert(first.toSeq != second.toSeq)
            }
        }
    }

    "with crypto absent from the global scope" - {

        // The Node-below-18 shape, which is the condition the original defect crashed on: a host with no Web Crypto global. Both backends
        // reach the module candidate and keep working, so the whole `SecureRandom` surface survives a host the old code raised a TypeError
        // on. This leaf is what states that end to end.
        "fillBytes still fills, because the crypto module candidate covers a host without the Web Crypto global" in {
            Sync.defer {
                val out = new Array[Byte](32)
                withoutCryptoGlobal {
                    SecureRandom.fillBytes(out)
                }
                assert(out.exists(_ != 0.toByte))
            }
        }

        "the Web Crypto candidate alone fails with SecureRandom.EntropyUnavailable rather than raising a JS TypeError" in {
            Abort.run[Throwable](Sync.defer {
                withoutCryptoGlobal {
                    SecureRandom.fillBytesFrom(Seq(Candidate.WebCryptoGlobal), new Array[Byte](32))
                }
            }).map {
                case Result.Failure(e: SecureRandom.EntropyUnavailable) =>
                    assert(e.getMessage().contains("globalThis.crypto.getRandomValues"))
                case other =>
                    fail(s"expected a typed SecureRandom.EntropyUnavailable failure, got $other")
            }
        }

        "the Web Crypto candidate alone succeeds once the global is back, so the leaf above fails for the right reason" in {
            Sync.defer {
                val out = new Array[Byte](32)
                SecureRandom.fillBytesFrom(Seq(Candidate.WebCryptoGlobal), out)
                assert(out.exists(_ != 0.toByte))
            }
        }

        "an unguarded dereference raises a bare TypeError, which is what the probe above exists to replace" in {
            // The expression this source used to be, run under the same window the two leaves above use. It is what shows those leaves are
            // keyed to the guard rather than to something incidental: the identical condition that makes them report EntropyUnavailable makes
            // this raise a JS TypeError, carrying no type a caller could catch and no statement of what was missing.
            Sync.defer {
                val raised =
                    try
                        withoutCryptoGlobal {
                            val buf = new Int8Array(8)
                            val _   = sjs.Dynamic.global.crypto.getRandomValues(buf)
                            "no error was raised"
                        }
                    catch
                        case e: sjs.JavaScriptException =>
                            e.exception.asInstanceOf[sjs.Dynamic].selectDynamic("name").asInstanceOf[String]
                assert(raised == "TypeError")
            }
        }
    }

    "fillBytesFrom" - {

        "fails with SecureRandom.EntropyUnavailable when the global crypto object has no getRandomValues" in {
            val partial = sjs.Dynamic.literal(subtle = sjs.Dynamic.literal())
            Abort.run[Throwable](Sync.defer {
                withGlobals("crypto" -> partial) {
                    SecureRandom.fillBytesFrom(Seq(Candidate.WebCryptoGlobal), new Array[Byte](32))
                }
            }).map {
                case Result.Failure(e: SecureRandom.EntropyUnavailable) =>
                    assert(e.getMessage().contains("globalThis.crypto.getRandomValues"))
                case other =>
                    fail(s"expected a typed SecureRandom.EntropyUnavailable failure, got $other")
            }
        }

        "fails with SecureRandom.EntropyUnavailable when no candidate is offered at all" in {
            Abort.run[Throwable](Sync.defer {
                SecureRandom.fillBytesFrom(Seq.empty, new Array[Byte](32))
            }).map {
                case Result.Failure(e: SecureRandom.EntropyUnavailable) =>
                    assert(e.getMessage().contains("no candidate entropy source was offered"))
                case other =>
                    fail(s"expected a typed SecureRandom.EntropyUnavailable failure, got $other")
            }
        }

        "leaves a zero-length request alone rather than probing for a source" in {
            Sync.defer {
                val out = new Array[Byte](0)
                SecureRandom.fillBytesFrom(Seq.empty, out)
                assert(out.length == 0)
            }
        }

        "writes every window of a buffer past the Web Crypto per-call ceiling" in {
            Sync.defer {
                val length = SecureRandom.webCryptoWindow + 4464
                val out    = Array.fill(length)(0.toByte)
                SecureRandom.fillBytesFrom(Seq(Candidate.WebCryptoGlobal), out)
                val emptyRegions = out.toSeq.grouped(4096).zipWithIndex.collect {
                    case (region, index) if !region.exists(_ != 0.toByte) => index
                }.toSeq
                assert(emptyRegions == Seq.empty[Int], s"windows left unwritten at 4096-byte indices $emptyRegions")
            }
        }
    }

    "the global window this suite opens" - {

        "closes even when the body inside it fails" in {
            Sync.defer {
                val before = sjs.typeOf(sjs.Dynamic.global.selectDynamic("crypto"))
                val seen =
                    try
                        withoutCryptoGlobal {
                            throw new IllegalStateException(sjs.typeOf(sjs.Dynamic.global.selectDynamic("crypto")))
                        }
                    catch case e: IllegalStateException => e.getMessage()
                assert(seen == "undefined")
                assert(sjs.typeOf(sjs.Dynamic.global.selectDynamic("crypto")) == before)
            }
        }
    }

    private def withoutCryptoGlobal[A](body: => A): A =
        withGlobals("crypto" -> sjs.undefined)(body)

    /** Replaces the named globals for the duration of `body`, then restores each one's original property descriptor, removing the property
      * again where the host never had it.
      *
      * `Object.defineProperty` rather than assignment: a host may publish `crypto` as an accessor, and Scala.js emits strict-mode code where
      * assigning to an accessor with no setter throws. Restoration sits in a `finally` so one failed assertion cannot leave the rest of the
      * suite without entropy, and the window holds only synchronous statements, so no other leaf interleaves with it.
      */
    private def withGlobals[A](bindings: (String, sjs.Any)*)(body: => A): A =
        // Reached through a selection rather than as `js.Dynamic.global` itself: Scala.js rejects loading the global scope as a value
        // anywhere but on the left of a `.`, and `globalThis` names the same object as a plain property of it.
        val global = sjs.Dynamic.global.selectDynamic("globalThis").asInstanceOf[sjs.Object]
        val saved  = bindings.map((name, _) => name -> sjs.Object.getOwnPropertyDescriptor(global, name))
        bindings.foreach { (name, value) =>
            val _ = sjs.Object.defineProperty(global, name, dataDescriptor(value))
        }
        try body
        finally
            saved.foreach { (name, descriptor) =>
                if sjs.isUndefined(descriptor) then sjs.special.delete(global, name)
                else
                    val _ = sjs.Object.defineProperty(global, name, descriptor)
            }
        end try
    end withGlobals

    private def dataDescriptor(value: sjs.Any): sjs.PropertyDescriptor =
        sjs.Dynamic.literal(value = value, writable = true, enumerable = false, configurable = true)
            .asInstanceOf[sjs.PropertyDescriptor]

end SecureRandomPlatformSpecificTest
