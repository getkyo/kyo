package kyo.internal.tasty.reader

import kyo.*

/** File-level attributes decoded from the optional TASTy `Attributes` section (Scala 3.3+).
  *
  * If the file has no `Attributes` section, use `FileAttributes.default` (all flags false, sourceFile absent).
  */
final case class FileAttributes(
    explicitNulls: Boolean,
    captureChecked: Boolean,
    isJava: Boolean,
    isOutline: Boolean,
    scala2StandardLibrary: Boolean,
    sourceFile: Maybe[String]
) derives CanEqual

object FileAttributes:
    val default: FileAttributes = FileAttributes(
        explicitNulls = false,
        captureChecked = false,
        isJava = false,
        isOutline = false,
        scala2StandardLibrary = false,
        sourceFile = Absent
    )
end FileAttributes
