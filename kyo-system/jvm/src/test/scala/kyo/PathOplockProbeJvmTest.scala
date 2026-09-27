package kyo

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Files as JFiles
import java.nio.file.Path as JPath

// Scratch probe, never merged: measures which oplocked handles below a directory let a rename of that directory proceed on Windows.
class PathOplockProbeJvmTest extends kyo.test.Test[Any]:

    import PathOplockProbeJvmTest.*

    override def config = super.config.sequential

    private def isWindows: Boolean =
        java.lang.System.getProperty("os.name", "").toLowerCase.contains("windows")

    "A: directory handle on sub without oplock refuses the ancestor rename" in {
        Sync.defer {
            if !isWindows then cancel("Windows only")
            val o = probe("A", onDirectory = true, oplockLevel = 0)
            assert(!o.renameOk && o.renameError == ErrorAccessDenied, o.line)
        }
    }

    "B: file handle on f.txt without oplock refuses the ancestor rename" in {
        Sync.defer {
            if !isWindows then cancel("Windows only")
            val o = probe("B", onDirectory = false, oplockLevel = 0)
            assert(!o.renameOk && o.renameError == ErrorAccessDenied, o.line)
        }
    }

    "C: directory handle on sub with an RH oplock" in {
        Sync.defer {
            if !isWindows then cancel("Windows only")
            val o = probe("C", onDirectory = true, oplockLevel = OplockCacheRead | OplockCacheHandle)
            if !o.granted then fail(s"oplock request refused: ${o.line}")
            assert(o.line.startsWith("OPLOCKPROBE C "))
        }
    }

    "D: file handle on f.txt with an RH oplock" in {
        Sync.defer {
            if !isWindows then cancel("Windows only")
            val o = probe("D", onDirectory = false, oplockLevel = OplockCacheRead | OplockCacheHandle)
            if !o.granted then fail(s"oplock request refused: ${o.line}")
            assert(o.line.startsWith("OPLOCKPROBE D "))
        }
    }

    "E: file handle on f.txt with an RWH oplock" in {
        Sync.defer {
            if !isWindows then cancel("Windows only")
            val o = probe("E", onDirectory = false, oplockLevel = OplockCacheRead | OplockCacheWrite | OplockCacheHandle)
            if !o.granted then fail(s"oplock request refused: ${o.line}")
            assert(o.line.startsWith("OPLOCKPROBE E "))
        }
    }

    "F: directory handle on sub with an R oplock" in {
        Sync.defer {
            if !isWindows then cancel("Windows only")
            val o = probe("F", onDirectory = true, oplockLevel = OplockCacheRead)
            if !o.granted then fail(s"oplock request refused: ${o.line}")
            assert(o.line.startsWith("OPLOCKPROBE F "))
        }
    }

    "G: JDK directory stream on sub refuses the ancestor rename" in {
        Sync.defer {
            if !isWindows then cancel("Windows only")
            val o = probeDirectoryStream()
            assert(!o.renameOk && o.renameError == ErrorAccessDenied, o.line)
        }
    }

end PathOplockProbeJvmTest

object PathOplockProbeJvmTest:

    val GenericRead                   = 0x80000000
    val FileListDirectory             = 0x1
    val FileShareAll                  = 0x7
    val OpenExisting                  = 3
    val FileFlagOverlapped            = 0x40000000
    val FileFlagBackupSemantics       = 0x02000000
    val FileFlagOpenRequiringOplock   = 0x00040000
    val FsctlRequestOplock            = 0x00090240
    val OplockCacheRead               = 1
    val OplockCacheHandle             = 2
    val OplockCacheWrite              = 4
    val RequestOplockInputFlagRequest = 1
    val RequestOplockCurrentVersion   = 1
    val RequestOplockInputSize        = 12
    val RequestOplockOutputSize       = 24
    val ErrorIoPending                = 997
    val ErrorAccessDenied             = 5
    val ErrorSharingViolation         = 32
    val WaitObject0                   = 0
    val BreakWaitMs                   = 3000
    val RenameJoinMs                  = 30000L

    final case class Outcome(
        letter: String,
        granted: Boolean,
        requestError: Int,
        breakFired: Boolean,
        newLevel: Int,
        outputFlags: Int,
        renameOk: Boolean,
        renameError: Int,
        renameMs: Long,
        renameFinishedBeforeAck: Boolean
    ):
        val line: String =
            s"OPLOCKPROBE $letter granted=$granted breakFired=$breakFired newLevel=$newLevel renameOk=$renameOk " +
                s"renameError=$renameError renameMs=$renameMs requestError=$requestError outputFlags=$outputFlags " +
                s"renameFinishedBeforeAck=$renameFinishedBeforeAck"
    end Outcome

    object Kernel32:
        private val linker          = Linker.nativeLinker()
        private val lookup          = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())
        private val captureLayout   = Linker.Option.captureStateLayout()
        private val lastErrorOffset =
            captureLayout.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))

        private def fn(name: String, desc: FunctionDescriptor, captures: Boolean): MethodHandle =
            val sym = lookup.find(name).orElseThrow()
            if captures then linker.downcallHandle(sym, desc, Linker.Option.captureCallState("GetLastError"))
            else linker.downcallHandle(sym, desc)
        end fn

        // HANDLE CreateFileW(LPCWSTR, DWORD access, DWORD share, LPSECURITY_ATTRIBUTES, DWORD disposition, DWORD flags, HANDLE template)
        val createFileW: MethodHandle =
            fn("CreateFileW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), true)
        // BOOL CloseHandle(HANDLE)
        val closeHandle: MethodHandle =
            fn("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS), false)
        // BOOL DeviceIoControl(HANDLE, DWORD code, LPVOID in, DWORD inSize, LPVOID out, DWORD outSize, LPDWORD returned, LPOVERLAPPED)
        val deviceIoControl: MethodHandle =
            fn(
                "DeviceIoControl",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                true
            )
        // HANDLE CreateEventW(LPSECURITY_ATTRIBUTES, BOOL manualReset, BOOL initialState, LPCWSTR name)
        val createEventW: MethodHandle =
            fn("CreateEventW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), true)
        // DWORD WaitForSingleObject(HANDLE, DWORD ms)
        val waitForSingleObject: MethodHandle =
            fn("WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), false)
        // BOOL GetOverlappedResult(HANDLE, LPOVERLAPPED, LPDWORD transferred, BOOL wait)
        val getOverlappedResult: MethodHandle =
            fn("GetOverlappedResult", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT), true)
        // BOOL MoveFileExW(LPCWSTR from, LPCWSTR to, DWORD flags)
        val moveFileExW: MethodHandle =
            fn("MoveFileExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), true)

        def newCapture(arena: Arena): MemorySegment = arena.allocate(captureLayout)
        def lastError(capture: MemorySegment): Int  = capture.get(JAVA_INT, lastErrorOffset)

        def call(mh: MethodHandle, args: AnyRef*): AnyRef = mh.invokeWithArguments(args*)
        def callInt(mh: MethodHandle, args: AnyRef*): Int =
            call(mh, args*).asInstanceOf[java.lang.Integer].intValue
        def callAddress(mh: MethodHandle, args: AnyRef*): MemorySegment =
            call(mh, args*).asInstanceOf[MemorySegment]

        def isInvalid(h: MemorySegment): Boolean = h.address() == -1L || h.address() == 0L

        def close(h: MemorySegment): Unit =
            discard(callInt(closeHandle, h))
    end Kernel32

    private def i(v: Int): AnyRef = Int.box(v)

    private def wide(arena: Arena, s: String): MemorySegment =
        arena.allocateFrom(s, StandardCharsets.UTF_16LE)

    final class RenameThread(from: String, to: String) extends java.lang.Thread("oplock-probe-rename"):
        setDaemon(true)
        @volatile var ok: Boolean       = false
        @volatile var error: Int        = 0
        @volatile var elapsedMs: Long   = -1L
        @volatile var finished: Boolean = false
        override def run(): Unit        =
            val arena = Arena.ofConfined()
            try
                val capture = Kernel32.newCapture(arena)
                val start   = java.lang.System.nanoTime()
                val result  =
                    Kernel32.callInt(Kernel32.moveFileExW, capture, wide(arena, from), wide(arena, to), i(0))
                elapsedMs = (java.lang.System.nanoTime() - start) / 1000000L
                ok = result != 0
                if !ok then error = Kernel32.lastError(capture)
            catch
                case t: Throwable =>
                    println(s"OPLOCKPROBE rename thread threw ${t.getClass.getName}: ${t.getMessage}")
            finally
                finished = true
                arena.close()
            end try
        end run
    end RenameThread

    final class Layout(val tmp: JPath):
        val ancestor: JPath = tmp.resolve("ancestor")
        val sub: JPath      = ancestor.resolve("root").resolve("sub")
        val file: JPath     = sub.resolve("f.txt")
        val renamed: JPath  = tmp.resolve("ancestor-renamed")
    end Layout

    def setup(): Layout =
        val l = Layout(JFiles.createTempDirectory("kyo-oplock-probe"))
        discard(JFiles.createDirectories(l.sub))
        discard(JFiles.write(l.file, "probe bytes".getBytes(StandardCharsets.UTF_8)))
        l
    end setup

    def deleteRecursively(root: JPath): Unit =
        try
            val stream = JFiles.walk(root)
            try
                stream.sorted(java.util.Comparator.reverseOrder()).forEach { p =>
                    try discard(JFiles.deleteIfExists(p))
                    catch
                        case t: Throwable =>
                            println(s"OPLOCKPROBE cleanup could not delete $p: ${t.getClass.getName}: ${t.getMessage}")
                }
            finally stream.close()
            end try
        catch
            case t: Throwable =>
                println(s"OPLOCKPROBE cleanup could not walk $root: ${t.getClass.getName}: ${t.getMessage}")

    private def joinRename(t: RenameThread): Unit =
        t.join(RenameJoinMs)
        if t.isAlive then println(s"OPLOCKPROBE rename thread still blocked after ${RenameJoinMs} ms")

    /** oplockLevel 0 means no oplock: the handle is held plainly while the rename runs. */
    def probe(letter: String, onDirectory: Boolean, oplockLevel: Int): Outcome =
        val l                    = setup()
        val arena                = Arena.ofShared()
        val capture              = Kernel32.newCapture(arena)
        var handle               = MemorySegment.NULL
        var handleOpen           = false
        var event                = MemorySegment.NULL
        var pendingIo            = false
        var rename: RenameThread = null
        var granted              = false
        var requestError         = 0
        var breakFired           = false
        var newLevel             = -1
        var outputFlags          = -1
        var finishedBeforeAck    = false
        try
            // open the handle below the ancestor
            val target    = if onDirectory then l.sub else l.file
            val access    = if onDirectory then FileListDirectory else GenericRead
            val baseFlags =
                FileFlagOverlapped | (if onDirectory then FileFlagBackupSemantics else 0)
            val flags = if oplockLevel != 0 then baseFlags | FileFlagOpenRequiringOplock else baseFlags
            handle = Kernel32.callAddress(
                Kernel32.createFileW,
                capture,
                wide(arena, target.toString),
                i(access),
                i(FileShareAll),
                MemorySegment.NULL,
                i(OpenExisting),
                i(flags),
                MemorySegment.NULL
            )
            if Kernel32.isInvalid(handle) then
                val err = Kernel32.lastError(capture)
                println(s"OPLOCKPROBE $letter CreateFileW failed error=$err target=$target")
                throw new IllegalStateException(s"CreateFileW failed with $err for $target")
            end if
            handleOpen = true

            // request the oplock
            val overlapped = arena.allocate(32, 8)
            val output     = arena.allocate(32, 8)
            if oplockLevel != 0 then
                event = Kernel32.callAddress(Kernel32.createEventW, capture, MemorySegment.NULL, i(1), i(0), MemorySegment.NULL)
                if Kernel32.isInvalid(event) then
                    throw new IllegalStateException(s"CreateEventW failed with ${Kernel32.lastError(capture)}")
                overlapped.set(ADDRESS, 24, event)
                val input = arena.allocate(16, 8)
                input.set(JAVA_SHORT, 0, RequestOplockCurrentVersion.toShort)
                input.set(JAVA_SHORT, 2, RequestOplockInputSize.toShort)
                input.set(JAVA_INT, 4, oplockLevel)
                input.set(JAVA_INT, 8, RequestOplockInputFlagRequest)
                output.set(JAVA_SHORT, 0, RequestOplockCurrentVersion.toShort)
                output.set(JAVA_SHORT, 2, RequestOplockOutputSize.toShort)
                val returned = arena.allocate(JAVA_INT)
                val ok       = Kernel32.callInt(
                    Kernel32.deviceIoControl,
                    capture,
                    handle,
                    i(FsctlRequestOplock),
                    input,
                    i(RequestOplockInputSize),
                    output,
                    i(RequestOplockOutputSize),
                    returned,
                    overlapped
                )
                if ok == 0 then
                    requestError = Kernel32.lastError(capture)
                    granted = requestError == ErrorIoPending
                    pendingIo = granted
                else
                    println(
                        s"OPLOCKPROBE $letter DeviceIoControl completed synchronously " +
                            s"original=${output.get(JAVA_INT, 4)} new=${output.get(JAVA_INT, 8)} flags=${output.get(JAVA_INT, 12)}"
                    )
                end if
                if !granted then
                    val o = Outcome(letter, granted, requestError, false, -1, -1, false, 0, -1L, false)
                    println(o.line)
                    return o
                end if
            end if

            // rename the ancestor from a separate thread
            rename = RenameThread(l.ancestor.toString, l.renamed.toString)
            rename.start()

            if oplockLevel != 0 then
                // observe the break, then acknowledge by closing the handle
                val waited = Kernel32.callInt(Kernel32.waitForSingleObject, event, i(BreakWaitMs))
                breakFired = waited == WaitObject0
                finishedBeforeAck = rename.finished
                if breakFired then
                    val transferred = arena.allocate(JAVA_INT)
                    val r           = Kernel32.callInt(Kernel32.getOverlappedResult, capture, handle, overlapped, transferred, i(0))
                    if r == 0 then
                        println(s"OPLOCKPROBE $letter GetOverlappedResult failed error=${Kernel32.lastError(capture)}")
                    newLevel = output.get(JAVA_INT, 8)
                    outputFlags = output.get(JAVA_INT, 12)
                    pendingIo = false
                end if
                Kernel32.close(handle)
                handleOpen = false
            end if
            joinRename(rename)
            if !finishedBeforeAck && oplockLevel == 0 then finishedBeforeAck = rename.finished

            val o = Outcome(
                letter,
                granted,
                requestError,
                breakFired,
                newLevel,
                outputFlags,
                rename.ok,
                rename.error,
                rename.elapsedMs,
                finishedBeforeAck
            )
            println(o.line)
            o
        finally
            if handleOpen then Kernel32.close(handle)
            if rename != null then joinRename(rename)
            if pendingIo then
                // the closed handle completes the pending oplock request; let it land before the arena frees the OVERLAPPED
                discard(Kernel32.callInt(Kernel32.waitForSingleObject, event, i(2000)))
            if event.address() != 0L then Kernel32.close(event)
            if rename == null || !rename.isAlive then arena.close()
            deleteRecursively(l.tmp)
        end try
    end probe

    def probeDirectoryStream(): Outcome =
        val l                    = setup()
        val stream               = JFiles.newDirectoryStream(l.sub)
        var rename: RenameThread = null
        try
            rename = RenameThread(l.ancestor.toString, l.renamed.toString)
            rename.start()
            joinRename(rename)
            val o = Outcome("G", false, 0, false, -1, -1, rename.ok, rename.error, rename.elapsedMs, rename.finished)
            println(o.line)
            if !rename.ok then
                try
                    discard(JFiles.move(l.ancestor, l.renamed))
                    println("OPLOCKPROBE G jdk Files.move succeeded while the directory stream was open")
                catch
                    case t: Throwable =>
                        println(s"OPLOCKPROBE G jdk Files.move threw ${t.getClass.getName}: ${t.getMessage}")
            end if
            o
        finally
            stream.close()
            if rename != null then joinRename(rename)
            deleteRecursively(l.tmp)
        end try
    end probeDirectoryStream

end PathOplockProbeJvmTest
