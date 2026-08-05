package kyo

import scala.annotation.tailrec
import scala.scalajs.js
import scala.scalajs.js.typedarray.Int8Array

/** Cryptographically secure entropy for the Scala.js and WebAssembly platforms.
  *
  * No entropy primitive is ambient across JS hosts, so the source is probed rather than assumed and a host exposing none of [[candidates]]
  * fails with [[SecureRandom.EntropyUnavailable]]. Nothing here falls back to `Math.random`; [[SecureRandom]] carries the reasoning for why
  * a silent downgrade is worse than a failure.
  *
  * Every probe reads a global through `js.typeOf` before reading its value. `js.Dynamic.global.selectDynamic(name)` compiles to a bare
  * reference to the global `name`, and reading an undeclared identifier raises a `ReferenceError` rather than yielding `undefined`, so
  * `typeof` is the only safe way to ask whether one exists. That is also why the value read sits inside the guard rather than beside it.
  *
  * The `java.security.SecureRandom` shim this platform ships (for `java.util.UUID.randomUUID` linkage) delegates to [[SecureRandom.live]]
  * rather than reaching this logic directly, so these members can stay `private[kyo]`.
  */
private[kyo] trait SecureRandomPlatformSpecific:

    private[kyo] def liveUnsafe: SecureRandom.Unsafe =
        new SecureRandom.Unsafe:
            def nextBytes(length: Int)(using AllowUnsafe): Span[Byte] =
                val arr = new Array[Byte](length)
                fillBytes(arr)(using Frame.internal)
                Span.fromUnsafe(arr)
            end nextBytes

    /** Largest byte count the Web Crypto API accepts in one `getRandomValues` call. A larger request raises `QuotaExceededError`, so a
      * larger buffer is filled one window at a time. `nextBytes` takes a caller-supplied length, so buffers past this size are reachable
      * through the public surface.
      */
    private[kyo] val webCryptoWindow: Int = 65536

    /** A host source [[fillBytes]] can draw from. No single one of these is present on every JS host, which is the whole reason this trait
      * probes instead of dereferencing.
      */
    private[kyo] enum Candidate derives CanEqual:

        /** `crypto.getRandomValues`, resolved as a global. Present in browsers, Deno, Bun, and Node versions that publish the Web Crypto
          * global; absent on older Node, where reading through it is what raised a bare `TypeError`.
          */
        case WebCryptoGlobal

        /** The `crypto` module, reached through `require`. Because the probe compiles to a bare global reference rather than a property read
          * on the global object, it resolves through the enclosing scope chain, so a module-level `require` binding satisfies it. That is
          * what covers a Node host with no Web Crypto global. A browser has no such binding and falls through instead of failing.
          *
          * A static `@JSImport` would be the portable spelling used elsewhere in kyo-core, and is deliberately avoided here: it makes the
          * linker emit an eager `require`/`import` of a Node builtin into every bundle that can reach `UUID.randomUUID`, which is nearly
          * every bundle, and that breaks browser builds of kyo-core.
          */
        case CryptoModule
    end Candidate

    /** The candidates [[fillBytes]] tries, most widely available first. */
    private[kyo] val candidates: Seq[Candidate] = Seq(Candidate.WebCryptoGlobal, Candidate.CryptoModule)

    /** Writes cryptographically secure bytes over every position of `bytes`, and leaves a zero-length array alone.
      *
      * @throws SecureRandom.EntropyUnavailable
      *   when the host exposes none of [[candidates]]
      */
    private[kyo] def fillBytes(bytes: Array[Byte])(using Frame): Unit =
        fillBytesFrom(candidates, bytes)

    /** Same as [[fillBytes]] against an explicit candidate list.
      *
      * Exposed so a host's absence of one source can be driven without a host that actually lacks it. Restricting to a single candidate is
      * what makes the failure edge reachable from a test: a Node version predating the Web Crypto global is not reachable from this build,
      * and the other candidate would otherwise rescue the call.
      *
      * @throws SecureRandom.EntropyUnavailable
      *   when none of `sources` is exposed by the host
      */
    private[kyo] def fillBytesFrom(sources: Seq[Candidate], bytes: Array[Byte])(using Frame): Unit =
        val len = bytes.length
        if len > 0 then
            resolve(sources) match
                case Absent => throw new SecureRandom.EntropyUnavailable(describe(sources))
                case Present(fill) =>
                    val buf = new Int8Array(len)
                    fill(buf)
                    @tailrec def loop(i: Int): Unit =
                        if i < len then
                            bytes(i) = buf(i)
                            loop(i + 1)
                    loop(0)
        end if
    end fillBytesFrom

    /** The first candidate the host exposes, or `Absent`. Probing stops at the first hit, so a host with the Web Crypto global never reaches
      * for a module.
      */
    @tailrec private def resolve(sources: Seq[Candidate]): Maybe[Int8Array => Unit] =
        if sources.isEmpty then Absent
        else
            probe(sources.head) match
                case Present(fill) => Present(fill)
                case Absent        => resolve(sources.tail)

    private def probe(candidate: Candidate): Maybe[Int8Array => Unit] =
        candidate match
            case Candidate.WebCryptoGlobal => webCryptoGlobal
            case Candidate.CryptoModule    => cryptoModule

    private def webCryptoGlobal: Maybe[Int8Array => Unit] =
        if js.typeOf(js.Dynamic.global.selectDynamic("crypto")) == "undefined" then Absent
        else
            val crypto = js.Dynamic.global.selectDynamic("crypto")
            if isCallable(crypto, "getRandomValues") then
                Present(windowed(buf => discardJs(crypto.applyDynamic("getRandomValues")(buf))))
            else Absent
        end if
    end webCryptoGlobal

    private def cryptoModule: Maybe[Int8Array => Unit] =
        if js.typeOf(js.Dynamic.global.selectDynamic("require")) != "function" then Absent
        else
            // A host can have `require` and still have no `crypto` module, and a thrown Error is the only signal for that, so catching here
            // is boundary detection rather than control flow. It is the same reason `NodeError` catches to classify Node errno values.
            val module =
                try Maybe(js.Dynamic.global.applyDynamic("require")("crypto"))
                catch case _: js.JavaScriptException => Absent
            module.flatMap { mod =>
                // randomFillSync fills in place and has no per-call ceiling. webcrypto is the same interface as the global, so it keeps the
                // windowing.
                if isCallable(mod, "randomFillSync") then
                    Present(buf => discardJs(mod.applyDynamic("randomFillSync")(buf)))
                else
                    val webcrypto = mod.selectDynamic("webcrypto")
                    if isCallable(webcrypto, "getRandomValues") then
                        Present(windowed(buf => discardJs(webcrypto.applyDynamic("getRandomValues")(buf))))
                    else Absent
                end if
            }
        end if
    end cryptoModule

    /** Names the candidates that were tried, as the detail of [[SecureRandom.EntropyUnavailable]], so a failure says what was looked for
      * rather than only that nothing was found.
      */
    private def describe(sources: Seq[Candidate]): String =
        if sources.isEmpty then "no candidate entropy source was offered"
        else sources.map(render).mkString("probed ", ", ", "")

    private def render(candidate: Candidate): String =
        candidate match
            case Candidate.WebCryptoGlobal => "globalThis.crypto.getRandomValues"
            case Candidate.CryptoModule    => "the crypto module through require"

    /** True when `target` is a value carrying a callable `member`. Both halves are checked: a host can publish a `crypto` object that has no
      * `getRandomValues` on it, and a module can lack the function being looked for.
      */
    private def isCallable(target: js.Dynamic, member: String): Boolean =
        !js.isUndefined(target) && target != null && js.typeOf(target.selectDynamic(member)) == "function"

    /** Adapts a fill capped at [[webCryptoWindow]] bytes per call into one that satisfies a buffer of any size. A subarray is a view over
      * the same memory, so filling each window fills the original buffer.
      */
    private def windowed(fill: Int8Array => Unit): Int8Array => Unit =
        buf =>
            if buf.length <= webCryptoWindow then fill(buf)
            else
                @tailrec def loop(from: Int): Unit =
                    if from < buf.length then
                        val until = Math.min(from + webCryptoWindow, buf.length)
                        fill(buf.subarray(from, until))
                        loop(until)
                loop(0)

    private def discardJs(value: js.Dynamic): Unit =
        val _ = value
end SecureRandomPlatformSpecific
