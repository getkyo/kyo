package kyo.internal.tasty.query

import kyo.*

/** JVM fallback: cold-load java.class.path exactly once.
  *
  * INV-009 site-2: the AllowUnsafe boundary is bounded to this object. The lazy val Tasty.current
  * calls initFallback at most once per JVM process; the result is cached in the lazy val.
  *
  * If loading fails for any reason (empty classpath, IO error, timeout), the fallback returns
  * Binding.empty so that queries return predictable empty results rather than panicking.
  */
private[kyo] object PlatformFallback:
    def initFallback: Binding =
        val cp: String = java.lang.System.getProperty("java.class.path", "")
        val roots: Seq[String] =
            cp.split(java.io.File.pathSeparatorChar).toIndexedSeq.filter(_.nonEmpty)
        if roots.isEmpty then Binding.empty
        else
            given AllowUnsafe = AllowUnsafe.embrace.danger
            // Frame.internal: required by coldLoadBinding; this is the INV-009 site-2 init boundary.
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
                case _: Throwable => Binding.empty
            end try
        end if
    end initFallback
end PlatformFallback
