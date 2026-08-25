package kyo.doctest.internal

import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

/** Child-process entry point used by RuntimeExecutor. */
private[kyo] object RuntimeExecutorMain:

    def main(args: Array[String]): Unit =
        val resultPath   = java.nio.file.Path.of(args(0))
        val progressPath = java.nio.file.Path.of(args(1))
        val outputDir    = java.nio.file.Path.of(args(2))
        val className    = args(3)
        val synthFile    = args(4)
        val _            = Files.writeString(progressPath, "started", StandardCharsets.UTF_8)
        val _            = java.lang.System.setProperty("kyo.doctest.progress", progressPath.toString)
        try
            val separator = java.io.File.pathSeparator
            val classpath = java.lang.System.getProperty("java.class.path", "")
                .split(java.util.regex.Pattern.quote(separator))
                .filter(_.nonEmpty)
                .map(path => java.nio.file.Path.of(path).toUri.toURL)
            val urls   = outputDir.toUri.toURL +: classpath
            val loader = new ChildFirstClassLoader(urls)
            try
                try
                    val _ = Class.forName(className, true, loader)
                    val _ = Files.writeString(resultPath, "completed\n", StandardCharsets.UTF_8)
                catch
                    case error: ClassNotFoundException =>
                        val result = s"load-failed\n${encode(error.getMessage)}\n"
                        val _      = Files.writeString(resultPath, result, StandardCharsets.UTF_8)
            finally loader.close()
            end try
        catch
            case error: Throwable =>
                val cause   = unwrap(error)
                val frame   = cause.getStackTrace.find(_.getFileName == synthFile)
                val file    = frame.fold("")(_.getFileName)
                val line    = frame.fold(0)(_.getLineNumber)
                val message = Option(cause.getMessage).getOrElse("")
                val result =
                    s"threw\n${encode(cause.getClass.getName)}\n${encode(message)}\n${encode(file)}\n$line\n"
                val _ = Files.writeString(resultPath, result, StandardCharsets.UTF_8)
        end try
    end main

    @scala.annotation.tailrec
    private def unwrap(error: Throwable): Throwable =
        error match
            case e: ExceptionInInitializerError if e.getCause != null => unwrap(e.getCause)
            case _                                                    => error

    private def encode(value: String): String =
        Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

    /** Loads the compiled block and its module dependencies without falling back to the runner's application class loader. */
    private class ChildFirstClassLoader(urls: Array[java.net.URL])
        extends URLClassLoader(urls, ClassLoader.getPlatformClassLoader):

        override protected def loadClass(name: String, resolve: Boolean): Class[?] =
            val loaded = findLoadedClass(name)
            val result =
                if loaded != null then loaded
                else
                    try findClass(name)
                    catch case _: ClassNotFoundException => super.loadClass(name, false)
            if resolve then resolveClass(result)
            result
        end loadClass
    end ChildFirstClassLoader

end RuntimeExecutorMain
