package kyo.internal

import kyo.*

/** Shared OS-specific directory construction logic.
  *
  * Both jvm-native and js `PathPlatformSpecific` extend this trait, providing only the 4 abstract primitives plus `temp` / `tempDir`.
  */
private[kyo] trait PathDirectories:

    // --- Abstract primitives (implemented per platform) ---

    protected def make(parts: Chunk[String]): Path

    /** Returns the value of the named environment variable, or `""` if unset / null. */
    protected def envOrEmpty(name: String): String

    /** The current user's home directory. */
    protected def homePath: Path

    /** Normalised OS tag: `"mac"`, `"linux"`, or `"win"`. */
    protected def osPlatform: String

    /** Creates a temporary file. Platform-specific. */
    protected def temp(prefix: String, suffix: String)(using Frame): Path < (Sync & Abort[FileFsException])

    /** Creates a temporary directory. Platform-specific. */
    protected def tempDir(prefix: String)(using Frame): Path < (Sync & Abort[FileFsException])

    // --- Concrete shared logic ---

    protected def makeChild(parent: Path, segment: String): Path =
        make(parent.parts ++ Chunk(segment))

    protected def platformBasePaths: Path.BasePaths =
        osPlatform match
            case "mac" => macBasePaths
            case "win" => windowsBasePaths
            case _     => linuxBasePaths

    protected def platformUserPaths: Path.UserPaths =
        val home = homePath
        osPlatform match
            case "mac" => macUserPaths(home)
            case "win" => windowsUserPaths(home)
            case _     => linuxUserPaths(home)
        end match
    end platformUserPaths

    protected def platformProjectPaths(qualifier: String, organization: String, application: String): Path.ProjectPaths =
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
    protected def tempScoped(
        prefix: String = "kyo",
        suffix: String = ".tmp"
    )(using Frame): Path < (Sync & Scope & Abort[FileFsException]) =
        temp(prefix, suffix).map { p =>
            Scope.acquireRelease(p) { q =>
                Abort.run[FileFsException](q.removeAll).unit
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

private[kyo] object PathDirectories:

    /** Converts a glob pattern to a Regex. Supports `*`, `**`, `?`, `[...]`, `[!...]`, `{a,b}`, and `\x` escapes. */
    def globToRegex(glob: String): scala.util.matching.Regex =
        val sb  = new StringBuilder("^")
        var i   = 0
        val len = glob.length
        while i < len do
            val c = glob.charAt(i)
            c match
                case '\\' if i + 1 < len =>
                    // Escape next character literally
                    sb.append("\\Q")
                    sb.append(glob.charAt(i + 1))
                    sb.append("\\E")
                    i += 2
                case '*' if i + 1 < len && glob.charAt(i + 1) == '*' =>
                    sb.append(".*")
                    i += 2
                case '*' =>
                    sb.append("[^/\\\\]*")
                    i += 1
                case '?' =>
                    sb.append("[^/\\\\]")
                    i += 1
                case '[' =>
                    // Pass through character class to regex
                    sb.append('[')
                    i += 1
                    // Handle negation: [! or [^ both mean negated class
                    if i < len && (glob.charAt(i) == '!' || glob.charAt(i) == '^') then
                        sb.append('^')
                        i += 1
                    // Copy until closing ]
                    var first = true
                    while i < len && (first || glob.charAt(i) != ']') do
                        if glob.charAt(i) == '\\' && i + 1 < len then
                            sb.append("\\\\")
                            i += 1
                            sb.append(glob.charAt(i))
                        else
                            sb.append(glob.charAt(i))
                        end if
                        first = false
                        i += 1
                    end while
                    if i < len then
                        sb.append(']')
                        i += 1
                case '{' =>
                    // Alternation: {a,b,c} -> (?:a|b|c)
                    sb.append("(?:")
                    i += 1
                    while i < len && glob.charAt(i) != '}' do
                        if glob.charAt(i) == ',' then
                            sb.append('|')
                        else if glob.charAt(i) == '\\' && i + 1 < len then
                            sb.append("\\Q")
                            sb.append(glob.charAt(i + 1))
                            sb.append("\\E")
                            i += 1
                        else
                            val inner = glob.charAt(i)
                            // Escape regex metacharacters inside alternation
                            if ".+^$|()".indexOf(inner) >= 0 then
                                sb.append('\\')
                            sb.append(inner)
                        end if
                        i += 1
                    end while
                    if i < len then i += 1 // skip '}'
                    sb.append(')')
                // Escape regex metacharacters
                case '.' | '(' | ')' | '+' | '|' | '^' | '$' | '@' | '%' =>
                    sb.append('\\')
                    sb.append(c)
                    i += 1
                case _ =>
                    sb.append(c)
                    i += 1
            end match
        end while
        sb.append("$")
        sb.toString.r
    end globToRegex

end PathDirectories
