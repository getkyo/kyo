package kyo.internal

import kyo.*

/** Shared OS-specific directory construction logic.
  *
  * Both jvm-native and js `PathPlatformSpecific` extend this trait, providing only the 4 abstract primitives plus `temp` / `tempDir`.
  */
private[kyo] trait PathDirectories:

    // --- Abstract primitives (implemented per platform) ---

    private[kyo] def make(parts: Chunk[String]): Path

    /** Returns the value of the named environment variable, or `""` if unset / null. */
    private[kyo] def envOrEmpty(name: String): String

    /** The current user's home directory. */
    private[kyo] def homePath: Path

    /** The current working directory at the time of the call.
      *
      * On JVM/Native this reads the `user.dir` system property; on Node it calls `process.cwd()`.
      * The value is captured eagerly — if the application changes its working directory at
      * runtime (e.g., via `process.chdir`), call this method again to get the new path.
      */
    private[kyo] def cwdPath: Path

    /** Normalised OS tag: `"mac"`, `"linux"`, or `"win"`. */
    private[kyo] def osPlatform: String

    /** Creates a temporary file. Platform-specific. */
    private[kyo] def temp(prefix: String, suffix: String)(using Frame): Path < (Sync & Abort[FileStructureException])

    /** Creates a temporary directory. Platform-specific. */
    private[kyo] def tempDirUnscoped(prefix: String)(using Frame): Path < (Sync & Abort[FileStructureException])

    // --- Concrete shared logic ---

    private[kyo] def makeChild(parent: Path, segment: String): Path =
        make(parent.parts ++ Chunk(segment))

    private[kyo] def platformBasePaths: Path.BasePaths =
        osPlatform match
            case "mac" => macBasePaths
            case "win" => windowsBasePaths
            case _     => linuxBasePaths

    private[kyo] def platformUserPaths: Path.UserPaths =
        val home = homePath
        osPlatform match
            case "mac" => macUserPaths(home)
            case "win" => windowsUserPaths(home)
            case _     => linuxUserPaths(home)
        end match
    end platformUserPaths

    private[kyo] def platformProjectPaths(qualifier: String, organization: String, application: String): Path.ProjectPaths =
        val base = platformBasePaths
        val app  = s"$organization.$application"
        Path.ProjectPaths(
            path = makeChild(base.data, app),
            cache = makeChild(base.cache, app),
            config = makeChild(base.config, app),
            data = makeChild(base.data, app),
            dataLocal = makeChild(base.dataLocal, app),
            preference = makeChild(base.preference, app),
            runtime = makeChild(base.runtime, app)
        )
    end platformProjectPaths

    /** Creates a temporary file and registers it for deletion when the enclosing Scope closes. */
    private[kyo] def tempScoped(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Scope & Abort[FileStructureException]) =
        temp(prefix, suffix).map { p =>
            Scope.acquireRelease(p) { q =>
                Abort.run[FileSystemException](Path.run(q.removeAll)).unit
            }
        }

    // --- Private env helpers ---

    private def envOrElse(name: String, fallback: => String): String =
        val v = envOrEmpty(name)
        if v.nonEmpty then v else fallback

    private def envPathOrElse(name: String, fallback: => Path): Path =
        val v = envOrEmpty(name)
        if v.nonEmpty then make(Chunk(v)) else fallback

    // --- Linux (XDG) ---

    private def linuxBasePaths: Path.BasePaths =
        val home = homePath
        Path.BasePaths(
            cache = envPathOrElse("XDG_CACHE_HOME", makeChild(home, ".cache")),
            config = envPathOrElse("XDG_CONFIG_HOME", makeChild(home, ".config")),
            data = envPathOrElse("XDG_DATA_HOME", make(home.parts ++ Chunk(".local", "share"))),
            dataLocal = envPathOrElse("XDG_DATA_HOME", make(home.parts ++ Chunk(".local", "share"))),
            executable = envPathOrElse("XDG_BIN_HOME", make(home.parts ++ Chunk(".local", "bin"))),
            preference = envPathOrElse("XDG_CONFIG_HOME", makeChild(home, ".config")),
            runtime = envPathOrElse("XDG_RUNTIME_DIR", make(home.parts ++ Chunk(".local", "run"))),
            tmp =
                val t = envOrEmpty("TMPDIR")
                if t.nonEmpty then make(Chunk(t)) else make(Chunk("", "tmp"))
        )
    end linuxBasePaths

    private def linuxUserPaths(home: Path): Path.UserPaths =
        Path.UserPaths(
            home = home,
            audio = envPathOrElse("XDG_MUSIC_DIR", makeChild(home, "Music")),
            desktop = envPathOrElse("XDG_DESKTOP_DIR", makeChild(home, "Desktop")),
            document = envPathOrElse("XDG_DOCUMENTS_DIR", makeChild(home, "Documents")),
            download = envPathOrElse("XDG_DOWNLOAD_DIR", makeChild(home, "Downloads")),
            font = make(home.parts ++ Chunk(".local", "share", "fonts")),
            picture = envPathOrElse("XDG_PICTURES_DIR", makeChild(home, "Pictures")),
            public = envPathOrElse("XDG_PUBLICSHARE_DIR", makeChild(home, "Public")),
            template = envPathOrElse("XDG_TEMPLATES_DIR", makeChild(home, "Templates")),
            video = envPathOrElse("XDG_VIDEOS_DIR", makeChild(home, "Videos"))
        )

    // --- macOS ---

    private def macBasePaths: Path.BasePaths =
        val home = homePath
        Path.BasePaths(
            cache = make(home.parts ++ Chunk("Library", "Caches")),
            config = make(home.parts ++ Chunk("Library", "Application Support")),
            data = make(home.parts ++ Chunk("Library", "Application Support")),
            dataLocal = make(home.parts ++ Chunk("Library", "Application Support")),
            executable = makeChild(home, "Applications"),
            preference = make(home.parts ++ Chunk("Library", "Preferences")),
            runtime = make(home.parts ++ Chunk("Library", "Application Support")),
            tmp =
                val t = envOrEmpty("TMPDIR")
                if t.nonEmpty then make(Chunk(t)) else make(Chunk("", "tmp"))
        )
    end macBasePaths

    private def macUserPaths(home: Path): Path.UserPaths =
        Path.UserPaths(
            home = home,
            audio = makeChild(home, "Music"),
            desktop = makeChild(home, "Desktop"),
            document = makeChild(home, "Documents"),
            download = makeChild(home, "Downloads"),
            font = make(home.parts ++ Chunk("Library", "Fonts")),
            picture = makeChild(home, "Pictures"),
            public = makeChild(home, "Public"),
            template = makeChild(home, "Templates"),
            video = makeChild(home, "Movies")
        )

    // --- Windows ---

    private def windowsBasePaths: Path.BasePaths =
        val home     = homePath
        val appdata  = envOrElse("APPDATA", make(home.parts ++ Chunk("AppData", "Roaming")).unsafe.show)
        val localapp = envOrElse("LOCALAPPDATA", make(home.parts ++ Chunk("AppData", "Local")).unsafe.show)
        Path.BasePaths(
            cache = makeChild(make(Chunk(localapp)), "cache"),
            config = make(Chunk(appdata)),
            data = make(Chunk(appdata)),
            dataLocal = make(Chunk(localapp)),
            executable = make(Chunk(localapp)),
            preference = make(Chunk(appdata)),
            runtime = make(Chunk(localapp)),
            tmp =
                val t = envOrElse("TEMP", envOrElse("TMP", make(home.parts ++ Chunk("AppData", "Local", "Temp")).unsafe.show))
                make(Chunk(t))
        )
    end windowsBasePaths

    private def windowsUserPaths(home: Path): Path.UserPaths =
        Path.UserPaths(
            home = home,
            audio = makeChild(home, "Music"),
            desktop = makeChild(home, "Desktop"),
            document = makeChild(home, "Documents"),
            download = makeChild(home, "Downloads"),
            font = make(home.parts ++ Chunk("AppData", "Local", "Microsoft", "Windows", "Fonts")),
            picture = makeChild(home, "Pictures"),
            public = makeChild(home, "Public"),
            template = make(home.parts ++ Chunk("AppData", "Roaming", "Microsoft", "Windows", "Templates")),
            video = makeChild(home, "Videos")
        )

end PathDirectories
