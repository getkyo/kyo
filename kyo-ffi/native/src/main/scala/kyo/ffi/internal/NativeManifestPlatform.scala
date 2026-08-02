package kyo.ffi.internal

import kyo.Chunk
import kyo.Maybe
import kyo.Maybe.Absent

/** Scala Native backing for the manifest-driven pre-check.
  *
  * Native links its C at build time (no runtime library load), so there is no bundled-native presence to assert
  * and no manifest to read: the pre-check is a genuine no-op here, exactly as [[NativeLoader.load]] is.
  */
object NativeManifestPlatform:

    /** No runtime manifests on Native. */
    def manifestTexts: Chunk[String] = Chunk.empty

    /** No runtime version resource on Native. */
    def runtimeVersion: Maybe[String] = Absent

    /** No presence to assert on Native; C is linked at build time. */
    def assertBundledPresent(libraryId: String, bundledPlatforms: Set[String]): Unit = ()
end NativeManifestPlatform
