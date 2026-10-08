package kyo.bench.arena

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import scala.jdk.CollectionConverters.*

object Registry:

    def classes(): Seq[Class[? <: ArenaBench[?]]] =
        findClasses(this.getClass.getPackage.getName)
            .map(_.asSubclass(classOf[ArenaBench[?]]))
            .sortBy(_.getSimpleName())

    def instantiate(cls: Class[? <: ArenaBench[?]]): ArenaBench[?] =
        cls.getConstructors.find(_.getParameterCount == 0) match
            case Some(ctor) =>
                try ctor.newInstance().asInstanceOf[ArenaBench[?]]
                catch case e: InvocationTargetException => throw e.getCause
            case None =>
                kyo.bug(s"Class ${cls.getSimpleName} does not have an empty constructor")
    end instantiate

    private def findClasses(packageName: String): Seq[Class[?]] =
        val resourcePath = Path.of(getClass.getResource(".").toURI())
        val targetPath   = resourcePath.toString.replace("test-", "")
        Files.list(Path.of(targetPath))
            .collect(Collectors.toList())
            .asScala.toSeq
            .map(_.getFileName.toString)
            .filter(name => name.endsWith("Bench.class") && name.toString != "ArenaBench.class")
            .map(line => getClass(line, packageName))
    end findClasses

    private def getClass(className: String, packageName: String): Class[?] =
        Class.forName(packageName + "." + className.substring(0, className.lastIndexOf('.')))

end Registry
