package kyo.ffi.sbt

import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import sbt.util.Logger

/** Reflection bridge from the Scala 2.12 sbt plugin to the Scala 3 codegen.
  *
  * The plugin is cross-built on 2.12 (sbt 1.x requirement). The codegen is a
  * Scala 3 library. We cannot statically `dependsOn` across major Scala versions.
  *
  * Strategy:
  *   - The plugin JAR bundles `kyo-ffi-codegen.jar` as a resource under
  *     `kyo-ffi-plugin/kyo-ffi-codegen.jar` (see `build.sbt`,  *     `Compile/resourceGenerators`).
  *   - At runtime we extract that JAR to a temp file and build a URLClassLoader
  *     whose URLs are: `[codegenJar, ...userDependencyClasspath]`. Parent is
  *     the JVM bootstrap (no plugin-class-loader parent, so there's no 2.12
  *     stdlib in scope to clash with 3.x).
  *   - The user's `Compile / dependencyClasspath` is passed in from the plugin
  *     and must contain scala3-library, scala3-tasty-inspector, and
  *     scala3-compiler, which it does automatically because the user depends
  *     on `kyo-ffi` (a Scala 3 library).
  */
private[sbt] object CodegenBridge {

    private val cachedLoader         = new AtomicReference[ClassLoader](null)
    private val cachedOverrideLoader = new AtomicReference[ClassLoader](null)
    private val cachedFingerprint    = new AtomicReference[String](null)

    /** Outcome of a single `generate` invocation.
      *
      * @param files     emitted Scala source files (one per discovered trait)
      * @param traits    extracted (fqcn, simpleName, packageName, library) tuples.
      *                  Used by the plugin to validate library ids (#10) and to
      *                  detect stale generated impls when a trait is deleted (#34).
      */
    final case class Generated(files: Seq[Path], traits: Seq[TraitInfo])
    final case class TraitInfo(fqcn: String, simpleName: String, packageName: String, library: String)

    def generate(
        tastyFiles: Seq[String],
        classpath: Seq[String],
        outputDir: Path,
        platform: String,
        libraryId: Option[String],
        strictBlocking: Boolean,
        strictCallbacks: Boolean,
        log: Logger,
        includeDirs: Seq[String] = Nil,
        codegenClasspathOverride: Seq[String] = Nil
    ): Generated = {
        val empty = Generated(Nil, Nil)
        val cl = getCodegenClassLoader(classpath, log, codegenClasspathOverride) match {
            case Some(c) => c
            case None =>
                log.warn(
                    "[kyo-ffi-plugin] Could not construct codegen classloader " +
                        "(bundled kyo-ffi-codegen.jar not found). ffiGenerate is a no-op."
                )
                return empty
        }

        val generatorCls =
            try cl.loadClass("kyo.ffi.codegen.FfiGenerator$")
            catch {
                case e: ClassNotFoundException =>
                    log.warn(
                        "[kyo-ffi-plugin] kyo.ffi.codegen.FfiGenerator not found on the codegen classloader; " +
                            "ffiGenerate is a no-op."
                    )
                    log.warn(s"[kyo-ffi-plugin] cause: ${e.getMessage}")
                    return empty
            }

        val module        = generatorCls.getField("MODULE$").get(null)
        val platformCls   = cl.loadClass("kyo.ffi.codegen.FfiGenerator$Platform")
        val platformValue = platformCls.getMethod("valueOf", classOf[String]).invoke(null, platform)

        val configObjCls  = cl.loadClass("kyo.ffi.codegen.FfiGenerator$Config$")
        val configObj     = configObjCls.getField("MODULE$").get(null)
        val defaultConfig = configObjCls.getMethod("default").invoke(configObj)

        val configCls = cl.loadClass("kyo.ffi.codegen.FfiGenerator$Config")
        val copyMethod = configCls.getMethods.find(_.getName == "copy").getOrElse(
            sys.error("[kyo-ffi-plugin] Could not locate FfiGenerator.Config#copy via reflection.")
        )
        val defaultExtra = configCls.getMethod("extraLibraries").invoke(defaultConfig)

        val someCls = cl.loadClass("scala.Some")
        val noneObj = cl.loadClass("scala.None$").getField("MODULE$").get(null)
        val libOpt: AnyRef = libraryId match {
            case Some(id) => someCls.getConstructor(classOf[Object]).newInstance(id).asInstanceOf[AnyRef]
            case None     => noneObj
        }

        val includeDirsSeq = toScalaSeq(cl, includeDirs)
        val config = copyMethod.invoke(
            defaultConfig,
            libOpt,
            defaultExtra.asInstanceOf[AnyRef],
            java.lang.Boolean.valueOf(strictBlocking),
            java.lang.Boolean.valueOf(strictCallbacks),
            includeDirsSeq
        )

        val tastySeq = toScalaSeq(cl, tastyFiles)
        val cpSeq    = toScalaSeq(cl, classpath)

        val generateMethod = generatorCls.getMethods.find(_.getName == "generate").getOrElse(
            sys.error("[kyo-ffi-plugin] Could not locate FfiGenerator#generate via reflection.")
        )

        val result = generateMethod.invoke(
            module,
            tastySeq,
            cpSeq,
            outputDir,
            platformValue,
            config
        )

        val resultCls = cl.loadClass("kyo.ffi.codegen.FfiGenerator$Result")
        val files     = resultCls.getMethod("files").invoke(result)
        val warnings  = resultCls.getMethod("warnings").invoke(result)
        val traits    = resultCls.getMethod("traits").invoke(result)

        val warningList = scalaSeqToJava(warnings)
        warningList.foreach(w => log.warn(w.toString))

        val filesList = scalaSeqToJava(files).map(_.asInstanceOf[Path])
        val traitList = scalaSeqToJava(traits).map { t =>
            val c   = t.asInstanceOf[AnyRef]
            val cls = c.getClass
            TraitInfo(
                fqcn = cls.getMethod("fqcn").invoke(c).asInstanceOf[String],
                simpleName = cls.getMethod("simpleName").invoke(c).asInstanceOf[String],
                packageName = cls.getMethod("packageName").invoke(c).asInstanceOf[String],
                library = cls.getMethod("library").invoke(c).asInstanceOf[String]
            )
        }
        Generated(filesList, traitList)
    }

    /** Build (and cache) a URLClassLoader containing the codegen JAR PLUS the codegen's
      * Scala 3 runtime deps (scala3-library, tasty-inspector, etc.). The user's compile-time
      * classpath is appended so kyo-ffi and its runtime friends are also visible (harmless;
      * codegen doesn't actually need those, but it doesn't hurt).
      *
      * When `codegenClasspathOverride` is non-empty, the codegen URLs come from those jar/dir
      * paths directly and the bundled-resource extraction is skipped. This is the in-repo
      * bootstrap path: the integration-test build hands the plugin the codegen project's own
      * classpath, so no bundled plugin resource (and no prior plugin compile + reload) is
      * required. When it is empty the bundled-resource path is used, the default for external
      * downstream consumers. The two paths use separate cache fields so neither can return the
      * other's loader.
      */
    private def getCodegenClassLoader(
        userClasspath: Seq[String],
        log: Logger,
        codegenClasspathOverride: Seq[String] = Nil
    ): Option[ClassLoader] = {
        if (codegenClasspathOverride.nonEmpty) {
            val existing = cachedOverrideLoader.get
            if (existing != null) return Some(existing)

            val urls = new java.util.ArrayList[URL]()
            codegenClasspathOverride.foreach(s => urls.add(new File(s).toURI.toURL))
            userClasspath.foreach(s => urls.add(new File(s).toURI.toURL))

            // Parent = bootstrap (via `null`) so there's no 2.12 stdlib clash.
            val loader = new URLClassLoader(urls.toArray(new Array[URL](0)), null)
            cachedOverrideLoader.set(loader)
            return Some(loader)
        }

        val existing = cachedLoader.get
        if (existing != null) return Some(existing)

        val bundled = extractBundledJars(log)
        if (bundled.isEmpty) return None

        val urls = new java.util.ArrayList[URL]()
        bundled.foreach(p => urls.add(p.toUri.toURL))
        userClasspath.foreach(s => urls.add(new File(s).toURI.toURL))

        // Parent = bootstrap (via `null`) so there's no 2.12 stdlib clash.
        val loader = new URLClassLoader(urls.toArray(new Array[URL](0)), null)
        cachedLoader.set(loader)
        Some(loader)
    }

    /** SHA-256 over the bundled codegen (`/kyo-ffi-plugin/bundled.txt` and every JAR it lists),
      * identifying the codegen version baked into this plugin build.
      *
      * ffiGenerate folds this into its `FileFunction.cached` config key so that a plugin or codegen
      * change with unchanged binding sources still invalidates the previously generated `*Impl.scala`.
      * Without it, such a change is a cache hit and the stale impl survives until a manual `clean`
      * (#255). Cached: the bundle is fixed for the JVM session. Returns "unknown" when the bundle is
      * absent, the case where ffiGenerate is already a no-op.
      */
    def codegenFingerprint(): String = codegenFingerprint(Nil)

    /** SHA-256 fingerprint of the codegen, override-aware.
      *
      * When `codegenClasspathOverride` is non-empty the fingerprint is the SHA-256 over the bytes
      * of those jar files (in sorted-path order for stability), so the in-repo bootstrap path stays
      * incrementally correct when the codegen classpath changes. Directory entries on the override
      * are folded in by path so a codegen rebuild still invalidates the cache. When it is empty the
      * bundled-resource fingerprint is used (and cached for the JVM session), the default path.
      */
    def codegenFingerprint(codegenClasspathOverride: Seq[String]): String = {
        if (codegenClasspathOverride.nonEmpty) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            codegenClasspathOverride.sorted.foreach { path =>
                md.update(path.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                val f = new File(path)
                if (f.isFile) {
                    val in = new java.io.FileInputStream(f)
                    try md.update(readAll(in))
                    finally in.close()
                }
            }
            return md.digest().map(b => "%02x".format(b)).mkString
        }
        val existing = cachedFingerprint.get
        if (existing != null) return existing
        val manifestStream = getClass.getResourceAsStream("/kyo-ffi-plugin/bundled.txt")
        val result =
            if (manifestStream == null) "unknown"
            else {
                val manifest =
                    try new String(readAll(manifestStream), java.nio.charset.StandardCharsets.UTF_8)
                    finally manifestStream.close()
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.update(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                manifest.split("\n").toList.filter(_.nonEmpty).foreach { name =>
                    val in = getClass.getResourceAsStream(s"/kyo-ffi-plugin/$name")
                    if (in != null)
                        try md.update(readAll(in))
                        finally in.close()
                }
                md.digest().map(b => "%02x".format(b)).mkString
            }
        cachedFingerprint.set(result)
        result
    }

    /** Extract every JAR listed in `/kyo-ffi-plugin/bundled.txt` to temp files. */
    private def extractBundledJars(log: Logger): Seq[Path] = {
        val manifestStream = getClass.getResourceAsStream("/kyo-ffi-plugin/bundled.txt")
        if (manifestStream == null) {
            log.warn("[kyo-ffi-plugin] bundled.txt not found in plugin JAR; codegen bundle missing.")
            return Nil
        }
        val manifest =
            try new String(readAll(manifestStream), java.nio.charset.StandardCharsets.UTF_8)
            finally manifestStream.close()

        manifest.split("\n").toList.filter(_.nonEmpty).flatMap { name =>
            val resPath = s"/kyo-ffi-plugin/$name"
            val in      = getClass.getResourceAsStream(resPath)
            if (in == null) {
                log.warn(s"[kyo-ffi-plugin] bundled resource $resPath missing from plugin JAR.")
                None
            } else {
                try {
                    val tmp = Files.createTempFile("kyo-ffi-bundle-", ".jar")
                    tmp.toFile.deleteOnExit()
                    val out = new FileOutputStream(tmp.toFile)
                    try {
                        val buf = new Array[Byte](8192)
                        var n   = in.read(buf)
                        while (n > 0) {
                            out.write(buf, 0, n)
                            n = in.read(buf)
                        }
                    } finally out.close()
                    Some(tmp)
                } finally in.close()
            }
        }
    }

    private def readAll(in: java.io.InputStream): Array[Byte] = {
        val out = new java.io.ByteArrayOutputStream
        val buf = new Array[Byte](8192)
        var n   = in.read(buf)
        while (n > 0) {
            out.write(buf, 0, n)
            n = in.read(buf)
        }
        out.toByteArray
    }

    private def toScalaSeq(cl: ClassLoader, xs: Seq[String]): AnyRef = {
        val nilObj = cl.loadClass("scala.collection.immutable.Nil$").getField("MODULE$").get(null)
        xs.foldRight(nilObj: AnyRef) { (head, tail) =>
            val consCls = cl.loadClass("scala.collection.immutable.$colon$colon")
            val ctor    = consCls.getConstructor(classOf[Object], cl.loadClass("scala.collection.immutable.List"))
            ctor.newInstance(head, tail).asInstanceOf[AnyRef]
        }
    }

    private def scalaSeqToJava(seq: AnyRef): List[Any] = {
        val iteratorM = seq.getClass.getMethod("iterator")
        val iter      = iteratorM.invoke(seq)
        val hasNextM  = iter.getClass.getMethod("hasNext")
        val nextM     = iter.getClass.getMethod("next")
        val buf       = scala.collection.mutable.ListBuffer.empty[Any]
        while (hasNextM.invoke(iter).asInstanceOf[java.lang.Boolean].booleanValue()) {
            buf += nextM.invoke(iter)
        }
        buf.toList
    }

    /** Compile a set of Scala 3 source files into TASTy files in `outputDir`, using the Scala 3
      * compiler bundled into the plugin JAR (via the reflective classloader).
      *
      * Returns the sequence of `.tasty` file paths produced in `outputDir`. Returns Nil if the
      * codegen classloader is unavailable, compilation fails, or there are no sources.
      *
      * This exists to fix the two-pass-compile problem: `ffiGenerate` needs TASTy for the user's
      * bindings trait, but on a first `compile` invocation the user's own Zinc compile pass hasn't
      * run yet (sourceGenerators execute before compile). By invoking Scalac directly on the trait
      * sources we materialize TASTy in a scratch directory, run codegen off that, and let the main
      * Zinc compile pick up both the trait sources AND the generated impl in one pass.
      */
    def compileSourcesToTasty(
        sources: Seq[File],
        classpath: Seq[String],
        outputDir: Path,
        log: Logger,
        codegenClasspathOverride: Seq[String] = Nil
    ): Seq[String] = {
        if (sources.isEmpty) return Nil
        val cl = getCodegenClassLoader(classpath, log, codegenClasspathOverride) match {
            case Some(c) => c
            case None =>
                log.warn("[kyo-ffi-plugin] compileSourcesToTasty: codegen classloader unavailable; skipping.")
                return Nil
        }
        Files.createDirectories(outputDir)

        // Build the Scalac classpath: bundled Scala 3 bits (reachable through the classloader's URLs)
        // plus the user's compile classpath. The reflective classloader above contains every bundled
        // JAR, we need its URL list for `-classpath`.
        val bundledCp: Seq[String] = cl match {
            case url: URLClassLoader => url.getURLs.toList.map(u => new File(u.toURI).getAbsolutePath)
            case _                   => Nil
        }
        val fullCp = (bundledCp ++ classpath).distinct.mkString(File.pathSeparator)

        val fixedArgs: Seq[String] = Seq(
            "-d",
            outputDir.toAbsolutePath.toString,
            "-classpath",
            fullCp,
            "-nowarn"
        )
        val args: Array[String] = (fixedArgs ++ sources.map(_.getAbsolutePath)).toArray

        try {
            val mainCls = cl.loadClass("dotty.tools.dotc.Main")
            val processM = mainCls.getMethods
                .find(m => m.getName == "process" && m.getParameterCount == 1)
                .getOrElse(sys.error("[kyo-ffi-plugin] dotty.tools.dotc.Main.process(String[]) not found"))
            val reporter   = processM.invoke(null, args.asInstanceOf[AnyRef])
            val hasErrorsM = reporter.getClass.getMethod("hasErrors")
            val hasErrors  = hasErrorsM.invoke(reporter).asInstanceOf[java.lang.Boolean].booleanValue()
            if (hasErrors) {
                log.warn("[kyo-ffi-plugin] compileSourcesToTasty: compilation reported errors; downstream codegen will be skipped.")
                return Nil
            }
        } catch {
            case t: Throwable =>
                log.warn(s"[kyo-ffi-plugin] compileSourcesToTasty failed: ${t.getClass.getName}: ${t.getMessage}")
                return Nil
        }

        // Walk outputDir for produced .tasty files.
        val produced = scala.collection.mutable.ListBuffer.empty[String]
        val stream   = Files.walk(outputDir)
        try {
            val it = stream.iterator()
            while (it.hasNext) {
                val p = it.next()
                if (p.toString.endsWith(".tasty")) produced += p.toAbsolutePath.toString
            }
        } finally stream.close()
        produced.toList
    }
}
