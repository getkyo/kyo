package kyo.ffi.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Emits the `package.json` that pins the koffi npm dependency for a kyo-ffi
  * JS consumer.
  *
  * kyo-ffi's runtime ABI probe (`kyo.ffi.internal.KoffiAbiProbe`) rejects any
  * koffi install whose `koffi.version` falls outside the supported `^2.7`
  * range. This template is the build-time counterpart: by writing it into
  * the Scala.js project alongside `fastLinkJS`, `npm install` picks the
  * pinned range instead of whichever `latest` is on the registry.
  *
  * The emitted JSON is intentionally minimal, only the fields needed for
  * `npm install` to succeed. Consumers are free to merge additional fields
  * into the file after generation (e.g. `"type": "commonjs"` for custom
  * bundlers); the plugin never overwrites an existing file unless the
  * caller asks it to.
  */
private[sbt] object NpmBundleTemplate {

    /** Supported koffi version range. **Must** stay lockstep with
      * `kyo.ffi.internal.FfiErrors.KoffiSupportedRange`, the runtime probe
      * and the build-time pin share the same contract.
      */
    val KoffiSupportedRange: String = "^2.7"

    /** JSON body of the generated `package.json`. `packageName` identifies
      * the JS project inside npm's metadata; `"private": true` prevents
      * accidental publication.
      */
    def packageJson(packageName: String): String =
        s"""{
           |  "name": "$packageName",
           |  "private": true,
           |  "dependencies": {
           |    "koffi": "$KoffiSupportedRange"
           |  }
           |}
           |""".stripMargin

    /** Write the template at `out`. Creates parent dirs as needed. If
      * `overwrite == false` (the default) and `out` already exists, the
      * method is a no-op, user customization wins.
      */
    def writeTemplate(out: File, packageName: String, overwrite: Boolean = false): Unit = {
        if (out.exists() && !overwrite) return
        val parent = out.getParentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        Files.write(out.toPath, packageJson(packageName).getBytes(StandardCharsets.UTF_8))
    }
}
