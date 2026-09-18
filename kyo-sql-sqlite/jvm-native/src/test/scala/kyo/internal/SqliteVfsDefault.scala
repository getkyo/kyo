package kyo.internal

/** The name SQLite registers its default VFS under here.
  *
  * Needed because a suite asserting that a NAMED vfs reaches the engine has to name one the engine actually has, and
  * that name is the operating system's rather than the Scala platform's: SQLite calls it `unix` everywhere it builds
  * on a POSIX layer and `win32` on Windows. Hard-coding `unix` passed on every host the suite had run on and failed
  * on a Windows runner with "no such vfs: unix".
  */
private[kyo] object SqliteVfsDefault:

    val name: String =
        if java.lang.System.getProperty("os.name", "").toLowerCase.contains("win") then "win32" else "unix"

end SqliteVfsDefault
