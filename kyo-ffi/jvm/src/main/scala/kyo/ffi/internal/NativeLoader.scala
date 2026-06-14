package kyo.ffi.internal

import java.io.File
import java.io.InputStream
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kyo.ffi.FfiUnsupported

/** JVM native library loader. Detects OS/arch, extracts bundled libraries with content-hash naming and advisory locking, falls back to
  * system-path lookup. Cached after first call per `libraryId`.
  */
object NativeLoader:
    private val loaded = new ConcurrentHashMap[String, SymbolLookup]()

    /** Return the cached [[java.lang.foreign.SymbolLookup]] for `libraryId`, loading it on first use. Rejects 32-bit hosts. */
    def load(libraryId: String): SymbolLookup =
        ensurePlatformChecked()
        loaded.computeIfAbsent(libraryId, id => loadLocked(id)).nn

    // --- 32-bit host rejection ---

    @volatile private var platformChecked: Boolean = false

    /** Run the 64-bit host check exactly once per process. */
    private def ensurePlatformChecked(): Unit =
        if !platformChecked then
            // Carrier-thread substrate: one-shot, double-checked module init latch (the 64-bit host check runs once per
            // process), not fiber coordination; off the scheduler-managed fiber path. See kyo-ffi/CONTRIBUTING.md.
            this.synchronized {
                if !platformChecked then
                    checkPlatform(detectIs64Bit())
                    platformChecked = true
                end if
            }
        end if
    end ensurePlatformChecked

    /** Detect whether the JVM runs on a 64-bit host via `sun.arch.data.model` and Panama `ValueLayout.ADDRESS.byteSize`. */
    def detectIs64Bit(): Boolean =
        val dataModel = sys.props.get("sun.arch.data.model")
        val panamaBytes =
            try ValueLayout.ADDRESS.nn.byteSize()
            catch case _: Throwable => 0L
        dataModel match
            case Some("64")     => panamaBytes == 0L || panamaBytes == 8L
            case Some("32")     => false
            case Some(_) | None => panamaBytes == 8L
        end match
    end detectIs64Bit

    /** Throw [[kyo.ffi.FfiUnsupported]] if not 64-bit. Exposed for unit tests. */
    def checkPlatform(isBit64: Boolean): Unit =
        if !isBit64 then
            val dm  = sys.props.get("sun.arch.data.model").getOrElse("unknown")
            val msg = FfiPlatformErrors.unsupported32BitHost(s"data model: $dm")
            throw new FfiUnsupported(msg)
        end if
    end checkPlatform

    /** Operating-system tag used for resource lookup. */
    enum Os derives CanEqual:
        case Linux, LinuxMusl, Darwin, Windows
        def libPrefix: String = this match
            case Linux | LinuxMusl | Darwin => "lib"
            case Windows                    => ""
        def libExtension: String = this match
            case Linux | LinuxMusl => "so"
            case Darwin            => "dylib"
            case Windows           => "dll"
        def tagName: String = this match
            case Linux     => "linux"
            case LinuxMusl => "linux-musl"
            case Darwin    => "darwin"
            case Windows   => "windows"
    end Os

    /** CPU-architecture tag used for resource lookup. */
    enum Arch derives CanEqual:
        case X86_64, Aarch64
        def tagName: String = this match
            case X86_64  => "x86_64"
            case Aarch64 => "aarch64"
    end Arch

    /** Detect the current operating system. */
    def detectOs: Os =
        val name = System.getProperty("os.name").nn.toLowerCase.nn
        if name.contains("linux") then
            if Files.exists(Paths.get("/lib/ld-musl-x86_64.so.1"))
                || Files.exists(Paths.get("/lib/ld-musl-aarch64.so.1"))
            then Os.LinuxMusl
            else Os.Linux
        else if name.contains("mac") then Os.Darwin
        else if name.contains("windows") then Os.Windows
        else throw new UnsupportedOperationException(s"Unsupported OS: $name")
        end if
    end detectOs

    /** Detect the current CPU architecture. */
    def detectArch: Arch =
        System.getProperty("os.arch") match
            case "amd64" | "x86_64"  => Arch.X86_64
            case "aarch64" | "arm64" => Arch.Aarch64
            case other               => throw new UnsupportedOperationException(s"Unsupported arch: $other")

    private def loadLocked(id: String): SymbolLookup =
        // security: do not set from untrusted input, resolves a filesystem path to load as native code.
        sys.props.get(s"kyo.ffi.$id.path") match
            case Some(p) => SymbolLookup.libraryLookup(Paths.get(p), Arena.global()).nn
            case None    => loadFromResourceOrSystem(id)

    private def loadFromResourceOrSystem(id: String): SymbolLookup =
        val os   = detectOs
        val arch = detectArch
        val ext  = os.libExtension
        val pfx  = os.libPrefix
        val path = s"/META-INF/native/${os.tagName}-${arch.tagName}/$pfx$id.$ext"
        // Track every candidate we try so a final not-found error can list them in order.
        val candidates = scala.collection.mutable.Buffer.empty[String]
        candidates += s"resource path: $path"
        val stream = getClass.getResourceAsStream(path)
        if stream == null then
            // Not bundled: resolve as a system library. Try dlopen by the literal id, then by the
            // platform-mapped filename (some named libs resolve by bare/mapped name; on macOS
            // `libc.dylib` resolves via the dyld shared cache where the bare name `"c"` does not).
            // If both dlopen attempts fail, fall back to the native linker's default lookup, which
            // carries libc / libm / system symbols (socket, epoll_create1, malloc, ...) on every
            // platform without naming a versioned SONAME. This fallback is required on Linux, where
            // `libc.so` is a GNU ld linker script that dlopen rejects (the loadable object is
            // `libc.so.6`); the default lookup reaches libc directly.
            //
            // Trade-off: a genuinely-missing NAMED library now surfaces as a symbol-not-found at
            // first `find` rather than a `LibraryNotFound` at load time. This is accepted because
            // bundled libraries are already resolved earlier via the resource path, and system
            // libraries (the common case: libc) legitimately live in the default lookup.
            def tryLookup(name: String): Option[SymbolLookup] =
                try Some(SymbolLookup.libraryLookup(name, Arena.global()).nn)
                catch case _: IllegalArgumentException => None

            candidates += s"system library: $id"
            val named: Option[SymbolLookup] =
                tryLookup(id).orElse {
                    val mapped = System.mapLibraryName(id).nn
                    if mapped != id then
                        candidates += s"system library: $mapped"
                        tryLookup(mapped)
                    else None
                    end if
                }
            named.getOrElse {
                candidates += "native linker default lookup"
                Linker.nativeLinker().nn.defaultLookup().nn
            }
        else
            try
                val tmp = extractToTemp(stream, pfx, id, ext)
                SymbolLookup.libraryLookup(tmp, Arena.global()).nn
            finally stream.close()
        end if
    end loadFromResourceOrSystem

    /** Resolve extraction directory: `-Dkyo.ffi.extractDir=` → `-Dkyo.ffi.tmpdir=/kyo-ffi` → `java.io.tmpdir/kyo-ffi`. */
    def resolveExtractDir(): Path =
        sys.props.get("kyo.ffi.extractDir") match
            case Some(explicit) => Paths.get(explicit).nn
            case None =>
                Paths.get(sys.props.getOrElse("kyo.ffi.tmpdir", sys.props("java.io.tmpdir")))
                    .resolve("kyo-ffi").nn
    end resolveExtractDir

    // Tracks files extracted by this JVM for the opt-in shutdown cleanup hook.
    private val extractedThisJvm      = ConcurrentHashMap.newKeySet[Path]().nn
    private val shutdownHookInstalled = new java.util.concurrent.atomic.AtomicBoolean(false)

    /** Install the opt-in shutdown cleanup hook (enabled by `-Dkyo.ffi.extractCleanup=true`). Installed at most once per JVM. */
    private def maybeInstallCleanupHook(): Unit =
        if sys.props.getOrElse("kyo.ffi.extractCleanup", "false") == "true" &&
            shutdownHookInstalled.compareAndSet(false, true)
        then
            val installEpochMs = java.lang.System.currentTimeMillis()
            Runtime.getRuntime.nn.addShutdownHook(
                new Thread(
                    () => cleanupExtractedFiles(installEpochMs),
                    "kyo-ffi-extract-cleanup"
                )
            )
        end if
    end maybeInstallCleanupHook

    /** Remove files extracted by this JVM with mtime >= `installEpochMs`. Exposed for unit tests. */
    private[ffi] def cleanupExtractedFiles(installEpochMs: Long): Unit =
        val it = extractedThisJvm.iterator().nn
        while it.hasNext do
            val p = it.next().nn
            try
                if Files.exists(p) then
                    val mtime = Files.getLastModifiedTime(p).nn.toMillis
                    if mtime >= installEpochMs then Files.deleteIfExists(p): Unit
                end if
            catch case _: Throwable => ()
            end try
        end while
    end cleanupExtractedFiles

    /** Remove a stale `.lck` file if no process holds it (via `FileChannel.tryLock`). Exposed for unit tests. */
    private[ffi] def tryCleanupStaleLock(lockFile: Path): Boolean =
        if !Files.exists(lockFile) then false
        else
            val ch =
                try FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE).nn
                catch case _: Throwable => null
            if ch == null then false
            else
                try
                    val lk =
                        try ch.tryLock()
                        catch case _: Throwable => null
                    if lk != null then
                        try
                            // Release before delete, on Windows `Files.delete` on a locked-then-unlocked file is permitted.
                            lk.release()
                            Files.deleteIfExists(lockFile)
                            true
                        catch case _: Throwable => false
                    else false
                    end if
                finally
                    try ch.close()
                    catch case _: Throwable => ()
                end try
            end if
        end if
    end tryCleanupStaleLock

    private def extractToTemp(in: InputStream, pfx: String, id: String, ext: String): Path =
        extractBytesToTemp(in.readAllBytes().nn, pfx, id, ext)

    /** Extract `bytes` to the configured directory using content-hash naming, advisory locking, and atomic rename. Exposed for tests. */
    private[ffi] def extractBytesToTemp(bytes: Array[Byte], pfx: String, id: String, ext: String): Path =
        val dir = resolveExtractDir()
        Files.createDirectories(dir)
        maybeInstallCleanupHook()
        val hex =
            val d  = MessageDigest.getInstance("SHA-256").nn.digest(bytes).nn
            val sb = new StringBuilder
            var i  = 0
            while i < 8 do
                sb.append("%02x".format(d(i) & 0xff))
                i += 1
            sb.toString
        end hex
        val out = dir.resolve(s"$pfx$id-$hex.$ext").nn
        if !Files.exists(out) then
            val lockFile = dir.resolve(s"$pfx$id-$hex.lck").nn
            // Opportunistically clean up stale `.lck` files owned by crashed peer processes so we don't block forever on their
            // advisory locks. If tryLock fails (live holder), we fall through to the normal blocking `ch.lock()` below.
            tryCleanupStaleLock(lockFile): Unit
            val ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).nn
            try
                val lk = ch.lock().nn
                try
                    if !Files.exists(out) then
                        writeAtomicRename(dir, out, bytes)
                        extractedThisJvm.add(out): Unit
                    end if
                finally lk.release()
                end try
            finally ch.close()
            end try
        end if
        out
    end extractBytesToTemp

    /** Write `bytes` to `out` via atomic rename from a temp file; falls back to non-atomic on unsupported filesystems. */
    private[ffi] def writeAtomicRename(dir: Path, out: Path, bytes: Array[Byte]): Unit =
        val tmp     = dir.resolve(s"${out.getFileName}.tmp-${UUID.randomUUID().toString}").nn
        var renamed = false
        try
            Files.write(tmp, bytes)
            try
                Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING): Unit
                renamed = true
            catch
                case _: AtomicMoveNotSupportedException =>
                    Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING): Unit
                    renamed = true
            end try
        finally
            if !renamed then
                try Files.deleteIfExists(tmp): Unit
                catch case _: Throwable => ()
            end if
        end try
    end writeAtomicRename
end NativeLoader
