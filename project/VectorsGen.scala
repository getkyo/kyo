import sbt.*

/** Source generator for a module's vendored test vectors: every file of every set under the module's `shared/src/test/vectors/`, verified
  * against its set's `MANIFEST` and embedded as one object per file in the package the caller names. Licence files are not embedded.
  */
object VectorsGen {

    private val Generator = "VectorsGen"

    /** Task body for `Test / sourceGenerators`: the sets under `vectorsDir`, emitted into package `pkg`; `module` is the module's directory
      * name, for the generated headers.
      */
    def generate(vectorsDir: File, outDir: File, pkg: String, module: String): Seq[File] = {
        val pkgDir = pkg.split('.').foldLeft(outDir)(_ / _)
        IO.createDirectory(pkgDir)
        val sets  = (vectorsDir * DirectoryFilter).get.sortBy(_.getName)
        val files = sets.flatMap { set =>
            val vendored = VendoredFiles.verified(set, Generator)
            val embedded = vendored.files.toSeq.sortBy(_._1).filterNot { case (name, _) =>
                name.startsWith("LICENSE") || name.startsWith("NOTICE")
            }
            val objects = embedded.map { case (name, bytes) =>
                val source = s"$module/shared/src/test/vectors/${set.getName}/$name"
                VendoredFiles.writeIfChanged(
                    pkgDir / s"Embedded${VendoredFiles.objectName(name)}.scala",
                    VendoredFiles.emitEmbeddedBytes(pkg, Generator, source, vendored.attribution, name, bytes)
                )
            }
            objects :+ VendoredFiles.writeIfChanged(
                pkgDir / s"Embedded${VendoredFiles.objectName(set.getName)}Set.scala",
                emitSetIndex(pkg, module, set.getName, vendored.attribution, embedded.map(_._1))
            )
        }
        VendoredFiles.requireDistinctNames(Generator, files)
    }

    // One object per set whose `files` maps each embedded file's name to its bytes, loaded on first use, so a test can walk a set.
    private def emitSetIndex(pkg: String, module: String, set: String, attribution: Seq[String], names: Seq[String]): String = {
        val name = s"Embedded${VendoredFiles.objectName(set)}Set"
        val sb   = new StringBuilder
        sb.append(VendoredFiles.header(Generator, s"$module/shared/src/test/vectors/$set/MANIFEST", attribution))
        sb.append(s"package $pkg\n\n")
        sb.append(s"object $name:\n")
        sb.append("    lazy val files: Map[String, () => kyo.Span[Byte]] = Map(\n")
        names.foreach(file => sb.append("        \"" + file + "\" -> (() => Embedded" + VendoredFiles.objectName(file) + ".bytes),\n"))
        sb.append("    )\n")
        sb.append(s"end $name\n")
        sb.toString
    }
}
