package kyo.test.sbt

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import scala.collection.mutable
import scala.scalanative.io.VirtualDirectory
import scala.scalanative.nir
import scala.util.control.NonFatal
import xsbti.PathBasedFile
import xsbti.VirtualFile
import xsbti.compile.ClassFileManager

/** Removes the Scala Native IR of every source whose classes Zinc deletes, so no `.nir` outlives the compile that should replace it.
  *
  * Zinc tracks `.class` and `.tasty` as a source's products and deletes them when the source is invalidated or removed, but it does not
  * know the `.nir` files Scala Native writes into the same directory. Many of those have no class file at all (`$$Lambda`, `$$anon` and
  * `$scalanative$ReflectivelyInstantiate$` classes), so they cannot be matched to deleted class files by name: a name prefix also
  * matches live IR, as `Foo$.nir` when only `class Foo` was removed. Each `.nir` records the source it was compiled from, so a deleted
  * class's own `.nir` names its source and every `.nir` from that source goes. Zinc recompiles that source in the same cycle, which
  * writes the live ones again.
  *
  * Removed files are moved aside and restored if the compile fails, as Zinc's transactional manager does for class files, so a failed
  * compile leaves the output directory as it found it.
  */
final private[sbt] class StaleNirFileManager(outputDir: Path) extends ClassFileManager {

    // Built on the first deletion, before Zinc writes anything, so it describes the IR the previous compile left.
    private lazy val sourceOfIr: Map[Path, String] =
        if (!Files.isDirectory(outputDir)) Map.empty
        else {
            val stream = Files.walk(outputDir)
            try {
                val builder = Map.newBuilder[Path, String]
                stream.iterator().forEachRemaining { path =>
                    if (path.toString.endsWith(".nir")) sourceOf(path).foreach(source => builder += path -> source)
                }
                builder.result()
            } finally stream.close()
        }

    private lazy val irOfSource: Map[String, Iterable[Path]] = sourceOfIr.groupBy(_._2).map { case (s, e) => s -> e.keys }

    private val moved                = mutable.LinkedHashMap.empty[Path, Path]
    private val generatedClasses     = mutable.ArrayBuffer.empty[Path]
    private lazy val backupDir: Path = Files.createTempDirectory("kyo-stale-nir")

    private def sourceOf(irFile: Path): Option[String] =
        try
            nir.serialization
                .deserializeBinary(VirtualDirectory.local(outputDir), outputDir.relativize(irFile))
                .collectFirst { case defn if defn.name.isInstanceOf[nir.Global.Top] => defn.pos.source }
                .collect { case source: nir.SourceFile.Relative => source.pathString }
        catch { case NonFatal(_) => None }

    override def delete(classes: Array[File]): Unit = removeIrOf(classes.iterator.map(_.toPath))

    override def delete(classes: Array[VirtualFile]): Unit =
        removeIrOf(classes.iterator.collect { case file: PathBasedFile => file.toPath })

    private def removeIrOf(classFiles: Iterator[Path]): Unit =
        classFiles.foreach { classFile =>
            val name = classFile.getFileName.toString
            if (name.endsWith(".class")) {
                val ownIr = classFile.resolveSibling(name.stripSuffix(".class") + ".nir")
                sourceOfIr.get(ownIr).foreach(source => irOfSource.getOrElse(source, Nil).foreach(moveAside))
            }
        }

    private def moveAside(file: Path): Unit =
        if (Files.exists(file) && !moved.contains(file)) {
            val backup = backupDir.resolve(moved.size.toString + ".nir")
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
            moved += file -> backup
        }

    override def generated(classes: Array[File]): Unit = generatedClasses ++= classes.iterator.map(_.toPath)

    override def generated(classes: Array[VirtualFile]): Unit =
        generatedClasses ++= classes.iterator.collect { case file: PathBasedFile => file.toPath }

    // On failure Zinc deletes the classes this compile generated and restores the ones it deleted. The IR follows: whatever the
    // compile wrote for the sources it generated classes from goes, and the IR moved aside comes back.
    override def complete(success: Boolean): Unit = {
        if (!success) {
            val written = generatedClasses.iterator
                .map(c => c.resolveSibling(c.getFileName.toString.stripSuffix(".class") + ".nir"))
                .filter(Files.exists(_))
                .flatMap(sourceOf)
                .toSet
            if (written.nonEmpty) {
                val stream = Files.walk(outputDir)
                try
                    stream.iterator().forEachRemaining { path =>
                        if (path.toString.endsWith(".nir") && sourceOf(path).exists(written)) Files.delete(path)
                    }
                finally stream.close()
            }
            moved.foreach { case (file, backup) => Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING) }
        }
        if (moved.nonEmpty) sbt.IO.delete(backupDir.toFile)
        moved.clear()
        generatedClasses.clear()
    }
}
