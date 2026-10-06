import sbt.*

/** Source generator for kyo-email's difference lists: every `.tsv` under a test resource directory's `kyo/internal/email/mime/`,
  * embedded as text in package `kyo.internal.email.mime` so a comparison with a reference reads its list without a classpath resource
  * and the shared lists run on every platform. The lists are the module's own files, kept by hand from the rows a failing test prints,
  * so they have no `MANIFEST`. The vendored vector sets go through [[VectorsGen]].
  */
object EmailTestVectorsGen {

    private val Generator = "EmailTestVectorsGen"

    /** Task body for `Test / sourceGenerators`; `source` is `dir` relative to the repository root. */
    def generateDifferences(dir: File, source: String, outDir: File): Seq[File] = {
        val pkgDir = outDir / "kyo" / "internal" / "email" / "mime"
        IO.createDirectory(pkgDir)
        val files = (dir * "*.tsv").get.sortBy(_.getName).map { file =>
            VendoredFiles.writeIfChanged(
                pkgDir / s"Embedded${VendoredFiles.objectName(file.getName)}.scala",
                VendoredFiles.emitEmbeddedText(
                    "kyo.internal.email.mime",
                    Generator,
                    s"$source/${file.getName}",
                    Seq.empty,
                    file.getName,
                    IO.read(file, java.nio.charset.StandardCharsets.UTF_8)
                )
            )
        }
        VendoredFiles.requireDistinctNames(Generator, files)
    }
}
