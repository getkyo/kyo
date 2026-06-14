package kyo.internal.tasty.query

import kyo.*

/** JVM fallback: cold-load java.class.path exactly once.
  *
  * The AllowUnsafe boundary is bounded to this object. The lazy val Tasty.global
  * calls initFallback at most once per JVM process; the result is cached in the lazy val.
  *
  * If loading fails for any reason (empty classpath, IO error, timeout), the fallback returns
  * Binding.empty so that queries return predictable empty results rather than panicking.
  */
private[kyo] object PlatformFallback:
    def initFallback: Binding =
        val classpath: String = java.lang.System.getProperty("java.class.path", "")
        val roots: Seq[String] =
            classpath.split(java.io.File.pathSeparatorChar).toIndexedSeq.filter(_.nonEmpty)
        if roots.isEmpty then Binding.empty
        else
            // Unsafe: JVM-only bootstrap path. PlatformFallback is invoked at most once per JVM
            // process from the Tasty.global lazy val; the platform-bridging boundary uses
            // KyoApp.Unsafe.runAndBlock to run the cold-load synchronously and return a plain
            // Binding (no effect row). runAndBlock requires AllowUnsafe; no Kyo effect entry above
            // this site can supply it because the fallback is the entry point itself.
            given AllowUnsafe = AllowUnsafe.embrace.danger
            // Frame.internal: required by coldLoadBinding; this is the AllowUnsafe init boundary.
            given Frame = Frame.internal
            try
                val result = KyoApp.Unsafe.runAndBlock(Duration.Infinity)(
                    ClasspathOrchestrator.coldLoadBinding(
                        roots,
                        kyo.Tasty.ErrorMode.SoftFail,
                        Maybe.Absent
                    )
                )
                result match
                    case Result.Success(binding) => binding
                    case _                       => Binding.empty
            catch
                // Unsafe: JVM boot-classpath load may fail (missing system property, malformed jar,
                // IO error, timeout) and we fall back to an empty binding so the module still loads.
                // The user-side withClasspath is the canonical entry point that returns typed errors.
                case _: Throwable => Binding.empty
            end try
        end if
    end initFallback
end PlatformFallback
