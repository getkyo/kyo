package kyo.ffi.internal

/** Platform-specific error / diagnostic message strings used by browser detection, koffi ABI probe, native loader, and reflection
  * instantiation. Kept separate from [[FfiErrors]] (runtime shared) and [[FfiGenErrors]] (generated-code messages) to make the audience of
  * each message clear.
  */
private[ffi] object FfiPlatformErrors:

    // --- 32-bit host rejection ---

    /** Message for [[kyo.ffi.FfiLoadError.Unsupported]] when a 32-bit host is detected (kyo-ffi requires 64-bit). */
    def unsupported32BitHost(detail: String): String =
        s"[kyo-ffi] kyo-ffi requires a 64-bit host; detected 32-bit ($detail). " +
            "32-bit support is not implemented."

    // --- Browser gate (JS only) ---

    /** Message for browser-runtime rejection in JS `NativeLoader.load`. */
    val BrowserUnsupportedLoader: String =
        "kyo-ffi does not support browser runtimes, neither `process` nor `require` is defined. " +
            "Supported Scala.js targets: Node.js, Bun, and Deno. Use the JVM or Scala Native targets for FFI outside Node-like environments."

    /** Message for browser-runtime rejection in JS `FfiReflect.instantiate`. */
    val BrowserUnsupportedReflect: String =
        "kyo-ffi does not support browser runtimes, neither `process` nor `require` is defined. " +
            "Supported Scala.js targets: Node.js, Bun, and Deno."

    // --- koffi ABI probe (JS only) ---

    /** Supported koffi npm-package version range. */
    val KoffiSupportedRange: String = "^2.7"

    /** Message for [[kyo.ffi.FfiLoadError.Unsupported]] when the installed koffi version is outside [[KoffiSupportedRange]]. */
    def koffiVersionMismatch(detected: String): String =
        s"kyo-ffi requires koffi $KoffiSupportedRange (2.7.x ≤ v < 3.0.0); detected $detected. " +
            "Pin the supported range in your package.json (`\"koffi\": \"^2.7\"`) and reinstall. " +
            "See kyo-ffi/README.md §JS runtime requirements."

    /** Message for [[kyo.ffi.FfiLoadError.Unsupported]] when a required koffi method is absent. */
    def koffiMissingMethod(method: String, detected: String): String =
        s"kyo-ffi requires koffi $KoffiSupportedRange; the installed koffi (version $detected) is missing method `$method`. " +
            "Reinstall koffi with `npm install koffi@^2.7` and retry. See kyo-ffi/README.md §JS runtime requirements."

    // --- FfiReflect.instantiate ---

    /** JVM message when the generated impl class cannot be found. */
    def implClassNotFoundJvm(implName: String, traitFqn: String): String =
        s"Cannot instantiate generated FFI impl '$implName' for binding '$traitFqn'. Did the kyo-ffi code generator run?"

    /** JVM message for [[java.lang.IllegalStateException]] thrown when the generated impl class lacks a public nullary constructor. */
    def implMissingNullaryCtorJvm(implName: String, traitFqn: String): String =
        s"Generated FFI impl '$implName' for binding '$traitFqn' has no public nullary constructor, regenerate with `sbt clean compile`."

    /** Scala Native message for [[java.lang.IllegalStateException]] thrown when scalanative-reflect cannot find the impl class. */
    def implClassNotFoundNative(implName: String, traitFqn: String): String =
        s"Cannot instantiate generated FFI impl '$implName' for binding '$traitFqn' via scalanative-reflect. Did the kyo-ffi code generator run? " +
            "The generated class must carry @scala.scalanative.reflect.annotation.EnableReflectiveInstantiation."

    /** Scala.js message for [[java.lang.IllegalStateException]] thrown when scalajs-reflect cannot find the impl class. */
    def implClassNotFoundJs(implName: String, traitFqn: String): String =
        s"Cannot instantiate generated FFI impl '$implName' for binding '$traitFqn' via scalajs-reflect. Did the kyo-ffi code generator run? " +
            "The generated class must carry @scala.scalajs.reflect.annotation.EnableReflectiveInstantiation."

    // --- NativeLoader ---

    /** Message for [[java.lang.UnsatisfiedLinkError]] when no bundled or system library is found. */
    def libraryNotFound(libraryId: String, candidates: List[String]): String =
        val list = candidates.zipWithIndex.map { case (c, i) => s"  ${i + 1}. $c" }.mkString("\n")
        s"Native library '$libraryId' not found. Searched the following candidates in order:\n" +
            s"$list\n" +
            s"Override with -Dkyo.ffi.$libraryId.path=<absolute path>."
    end libraryNotFound

    /** Message for the [[java.lang.UnsupportedOperationException]] thrown by Native `NativeLoader.load`, Native has no runtime loader. */
    def nativeLoaderNotApplicable(libraryId: String): String =
        s"NativeLoader.load('$libraryId') is not applicable on Scala Native, libraries are linked at binary build time."

end FfiPlatformErrors
