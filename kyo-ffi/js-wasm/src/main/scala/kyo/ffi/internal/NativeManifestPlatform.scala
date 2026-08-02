package kyo.ffi.internal

import kyo.Chunk
import kyo.Maybe
import kyo.Maybe.Absent

/** JS / Wasm backing for the manifest-driven pre-check.
  *
  * Scala.js links to a single JS module with no Java classpath, so the `META-INF/kyo-ffi/native-manifest`
  * resources are not readable at runtime and this supplies no manifest text. The JS loader instead performs a
  * real presence check when the generated impl resolves its library at load time (see [[NativeLoader.jsResolve]]),
  * which raises [[kyo.ffi.FfiLoadError.LibraryNotFound]] directly; JS module init is not permanently poisoned by a
  * throwing initializer the way a JVM `<clinit>` is, so the catchable error there is sufficient.
  */
object NativeManifestPlatform:

    /** No classpath manifests on JS / Wasm. */
    def manifestTexts: Chunk[String] = Chunk.empty

    /** No runtime version resource on JS / Wasm. */
    def runtimeVersion: Maybe[String] = Absent

    /** No manifest-driven pre-check on JS / Wasm; presence is asserted by the loader at resolve time. */
    def assertBundledPresent(libraryId: String, bundledPlatforms: Set[String]): Unit = ()
end NativeManifestPlatform
