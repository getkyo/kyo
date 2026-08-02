package kyo

/** Cryptographically secure random bytes.
  *
  * Use this for SCRAM nonces, OAEP seeds, keys, session tokens, and any context where prediction resistance matters; for
  * non-cryptographic randomness use [[Random]]. `SecureRandom` mirrors [[Random]]'s architecture: a safe and an unsafe tier, a [[live]]
  * default, an ambient instance scoped with [[let]], and accessors that read the ambient. The vocabulary is deliberately one operation,
  * because the secure use cases are byte requests.
  *
  * Each platform resolves its own source: the JVM through the JDK's `java.security.SecureRandom` provider; Native through `/dev/urandom`,
  * falling back to `/dev/random` (and `BCryptGenRandom` on Windows); JS and WebAssembly by probing `globalThis.crypto.getRandomValues`,
  * then the Node `crypto` module, since no single one of those is present on every host. A host that offers none of its platform's sources
  * fails with [[SecureRandom.EntropyUnavailable]]. Nothing here degrades to a non-cryptographic generator such as `Math.random`: a silent
  * downgrade would keep producing nonces and seeds while making them guessable, which is worse for every caller than a loud failure.
  *
  * Note: this type shadows `java.security.SecureRandom` for JVM code that imports both, the same way `kyo.System` shadows
  * `java.lang.System`. Spell the JDK type fully qualified where both are in scope.
  *
  * @see
  *   [[SecureRandom.live]] for the default secure generator
  * @see
  *   [[SecureRandom.let]] for installing a deterministic generator in tests
  * @see
  *   [[Random]] for non-cryptographic randomness
  */
abstract class SecureRandom extends Serializable:

    /** Generates `length` cryptographically secure random bytes. */
    def nextBytes(length: Int)(using Frame): Span[Byte] < Sync

    def unsafe: SecureRandom.Unsafe
end SecureRandom

object SecureRandom extends SecureRandomPlatformSpecific:

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe extends Serializable:
        def nextBytes(length: Int)(using AllowUnsafe): Span[Byte]
        def safe: SecureRandom = SecureRandom(this)
    end Unsafe

    /** Creates a new SecureRandom instance from an Unsafe implementation.
      *
      * @param u
      *   The Unsafe implementation to use.
      * @return
      *   A new SecureRandom instance.
      */
    def apply(u: Unsafe): SecureRandom =
        new SecureRandom:
            def nextBytes(length: Int)(using Frame): Span[Byte] < Sync =
                Sync.Unsafe.defer(u.nextBytes(length))
            def unsafe: Unsafe = u

    /** The platform's own secure source, resolved lazily so that referencing this value does not eagerly touch a host provider. */
    val live: SecureRandom = SecureRandom(liveUnsafe)

    private val local = Local.init(live)

    /** Executes the given effect with a specific SecureRandom instance installed as the ambient source.
      *
      * @param sr
      *   The SecureRandom instance to use.
      * @param v
      *   The effect to execute.
      * @return
      *   The result of the effect execution.
      */
    def let[A, S](sr: SecureRandom)(v: A < S)(using Frame): A < (S & Sync) =
        local.let(sr)(v)

    /** Gets the current SecureRandom instance from the local context. */
    def get(using Frame): SecureRandom < Any = local.get

    /** Executes a function that requires a SecureRandom instance using the current ambient instance.
      *
      * @param f
      *   A function that takes a SecureRandom instance and returns an effect.
      * @return
      *   The result of executing the function with the current SecureRandom instance.
      */
    def use[A, S](f: SecureRandom => A < S)(using Frame): A < (S & Sync) =
        local.use(f)

    /** Generates `length` cryptographically secure random bytes from the ambient instance. */
    def nextBytes(length: Int)(using Frame): Span[Byte] < Sync =
        Sync.Unsafe.withLocal(local)(_.unsafe.nextBytes(length))

    /** Signals that the host platform exposes no cryptographically secure entropy source, so [[SecureRandom]] cannot produce bytes.
      *
      * Reachable on JS, WebAssembly, and Native only; a JVM always has a `java.security.SecureRandom` provider.
      *
      * `nextBytes` on the unsafe tier is fixed by the [[SecureRandom.Unsafe]] contract and cannot return a `Result`, so the platform source
      * raises this as a throwable. It therefore reaches a caller the way any throwable raised inside `Sync` does: as an `Abort` failure
      * carrying this type where a handler covers it (`Abort.run[Throwable]`, or `Abort.run[SecureRandom.EntropyUnavailable]`), and as a
      * `Panic` where none does. Both shapes are appropriate, because an absent entropy provider is a property of the host rather than a
      * domain outcome most callers can recover from, while a caller that does want to branch on it has a named type to catch.
      *
      * @param detail
      *   the sources that were probed and found unusable
      */
    final class EntropyUnavailable(detail: String)(using Frame)
        extends KyoException("No cryptographically secure entropy source is available on this platform.", detail)

end SecureRandom
