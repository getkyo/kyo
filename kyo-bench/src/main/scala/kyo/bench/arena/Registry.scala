package kyo.bench.arena

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import scala.jdk.CollectionConverters.*

object Registry:

    def loadAll(): Seq[ArenaBench[?]] =
        val packageName = this.getClass.getPackage.getName
        val classes =
            findClasses(packageName)
                .filter(_.getSimpleName.endsWith("Bench"))
                .filter(_.getSimpleName != "Bench")
                .sortBy(_.getSimpleName())

        classes.map(cls =>
            val constructor = cls.getConstructors.find(_.getParameterCount == 0)
            constructor match
                case Some(ctor) =>
                    ctor.newInstance().asInstanceOf[ArenaBench[?]]
                case None =>
                    kyo.bug(s"Class ${cls.getSimpleName} does not have an empty constructor")
            end match
        )
    end loadAll

    private def findClasses(packageName: String): Seq[Class[?]] =
        val stream: InputStream = getClass.getClassLoader()
            .getResourceAsStream(packageName.replaceAll("[.]", File.separator))
        val reader = new BufferedReader(new InputStreamReader(stream))
        reader.lines()
            .filter(line => line.endsWith(".class"))
            .map(line => getClass(line, packageName))
            .collect(java.util.stream.Collectors.toList())
            .asScala
            .toSeq
    end findClasses

    private def getClass(className: String, packageName: String): Class[?] =
        Class.forName(packageName + "." + className.substring(0, className.lastIndexOf('.')))

end Registry
