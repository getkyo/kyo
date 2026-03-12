package kyo.internal.tui2

import kyo.*
import kyo.Maybe.*
import kyo.internal.tui2.widget.ValueResolver
import scala.annotation.tailrec

/** Platform-specific commands: OS detection, terminal image protocol detection, browser opening, and file picker dialogs.
  *
  * OS and image protocol are evaluated once at class load — no per-frame cost.
  */
private[kyo] object PlatformCmd:
    // ---- OS detection (evaluated once at class load) ----
    private val Os_Linux   = 0
    private val Os_MacOS   = 1
    private val Os_Windows = 2

    private val os: Int =
        val name = java.lang.System.getProperty("os.name", "").toLowerCase
        if name.contains("mac") then Os_MacOS
        else if name.contains("win") then Os_Windows
        else Os_Linux
    end os

    // ---- Terminal image protocol detection (evaluated once at class load) ----
    inline val NoImage  = 0
    inline val ITermImg = 1
    inline val KittyImg = 2

    val imageProtocol: Int =
        val termProgram = env("TERM_PROGRAM")
        val term        = env("TERM")
        val kittyPid    = env("KITTY_PID")
        val wezterm     = env("WEZTERM_EXECUTABLE")

        if kittyPid.nonEmpty then KittyImg
        else if termProgram == "iTerm.app" then ITermImg
        else if termProgram == "WezTerm" || wezterm.nonEmpty then ITermImg
        else if termProgram == "mintty" then ITermImg
        else if term == "xterm-kitty" then KittyImg
        else NoImage
        end if
    end imageProtocol

    /** Read env var, returning "" instead of null. */
    private def env(key: String): String =
        val v = java.lang.System.getenv(key)
        if v == null then "" else v

    /** Open URL in system browser. Fire-and-forget via Process.Command. */
    def openBrowser(url: String)(using Frame, AllowUnsafe): Unit =
        val cmd = os match
            case Os_MacOS   => Process.Command("open", url)
            case Os_Windows => Process.Command("cmd", "/c", "start", url)
            case _          => Process.Command("xdg-open", url)
        ValueResolver.runHandler(cmd.spawn.unit)
    end openBrowser

    /** Open OS-native file picker dialog. Blocks until user selects or cancels. Returns Absent if cancelled, no picker available, or error.
      */
    def openFilePicker(filter: String)(using Frame, AllowUnsafe): Maybe[String] =
        val maybeCmd = os match
            case Os_MacOS =>
                val filterClause =
                    if filter.isEmpty then ""
                    else
                        val types = filter.split(',').map(_.trim.stripPrefix(".")).mkString("\", \"")
                        s""" of type {"$types"}"""
                Present(Process.Command(
                    "osascript",
                    "-e",
                    s"""tell application "System Events"
                       |set f to choose file$filterClause
                       |POSIX path of f
                       |end tell""".stripMargin
                ))

            case Os_Windows =>
                val psFilter =
                    if filter.isEmpty then "All Files (*.*)|*.*"
                    else
                        val exts = filter.split(',').map(_.trim).mkString(";")
                        s"Files ($exts)|$exts"
                Present(Process.Command(
                    "powershell",
                    "-Command",
                    s"""Add-Type -AssemblyName System.Windows.Forms;
                       |$$f = New-Object System.Windows.Forms.OpenFileDialog;
                       |$$f.Filter = '$psFilter';
                       |if ($$f.ShowDialog() -eq 'OK') { $$f.FileName }""".stripMargin
                ))

            case _ =>
                findLinuxPicker(filter)

        maybeCmd.flatMap { cmd =>
            Result.apply[String] {
                Sync.Unsafe.evalOrThrow(cmd.text)
            }.toMaybe.flatMap { output =>
                val trimmed = output.trim
                Maybe.when(trimmed.nonEmpty)(trimmed)
            }
        }
    end openFilePicker

    private def findLinuxPicker(filter: String)(using Frame, AllowUnsafe): Maybe[Process.Command] =
        val hasZenity = commandExists("zenity")
        if hasZenity then
            val filterOpt =
                if filter.isEmpty then Seq.empty
                else
                    val exts = filter.split(',').map(_.trim).map(e => s"*$e").mkString(" ")
                    Seq(s"--file-filter=Files | $exts")
            Present(Process.Command(("zenity" +: "--file-selection" +: filterOpt)*))
        else
            val hasKdialog = commandExists("kdialog")
            if hasKdialog then
                val kFilter =
                    if filter.isEmpty then "*"
                    else filter.split(',').map(_.trim).map(e => s"*$e").mkString(" ")
                Present(Process.Command("kdialog", "--getopenfilename", ".", kFilter))
            else
                Absent
            end if
        end if
    end findLinuxPicker

    private def commandExists(name: String)(using Frame, AllowUnsafe): Boolean =
        Result.apply[Int] {
            Sync.Unsafe.evalOrThrow(Process.Command("which", name).waitFor)
        }.fold(_ == 0, _ => false, _ => false)

    // ---- Clipboard reading (cold path — Ctrl+V only) ----

    /** Read all available clipboard items. Queries types, then reads each. */
    def clipboardReadAll()(using Frame, AllowUnsafe): Chunk[InputEvent.ClipboardItem] =
        val types = clipboardTypes()
        if types.isEmpty then Chunk.empty
        else
            val builder = ChunkBuilder.init[InputEvent.ClipboardItem]
            @tailrec def loop(i: Int): Chunk[InputEvent.ClipboardItem] =
                if i >= types.size then builder.result
                else
                    clipboardRead(types(i)).foreach { data =>
                        builder.addOne(InputEvent.ClipboardItem(types(i), data))
                    }
                    loop(i + 1)
            loop(0)
        end if
    end clipboardReadAll

    /** Query available MIME types from OS clipboard. Returns empty Chunk on failure. */
    private def clipboardTypes()(using Frame, AllowUnsafe): Chunk[String] =
        os match
            case Os_MacOS   => macClipboardTypes()
            case Os_Linux   => linuxClipboardTypes()
            case Os_Windows => windowsClipboardTypes()

    /** Read clipboard data for a specific MIME type. Returns Absent on failure. */
    private def clipboardRead(mimeType: String)(using Frame, AllowUnsafe): Maybe[Array[Byte]] =
        os match
            case Os_MacOS   => macClipboardRead(mimeType)
            case Os_Linux   => linuxClipboardRead(mimeType)
            case Os_Windows => windowsClipboardRead(mimeType)

    // ---- macOS clipboard via JXA (osascript -l JavaScript) ----

    private def macClipboardTypes()(using Frame, AllowUnsafe): Chunk[String] =
        val script =
            """ObjC.import('AppKit');
              |var pb = $.NSPasteboard.generalPasteboard;
              |var types = pb.types;
              |var result = [];
              |for (var i = 0; i < types.count; i++) {
              |  result.push(ObjC.unwrap(types.objectAtIndex(i)));
              |}
              |result.join('\n');""".stripMargin
        Result.apply[String] {
            Sync.Unsafe.evalOrThrow(Process.Command("osascript", "-l", "JavaScript", "-e", script).text)
        }.toMaybe.fold(Chunk.empty) { output =>
            val lines = output.trim.split('\n')
            Chunk.from(lines.filter(_.nonEmpty))
        }
    end macClipboardTypes

    private def macClipboardRead(mimeType: String)(using Frame, AllowUnsafe): Maybe[Array[Byte]] =
        val script =
            s"""ObjC.import('AppKit');
               |ObjC.import('Foundation');
               |var pb = $$.NSPasteboard.generalPasteboard;
               |var data = pb.dataForType('$mimeType');
               |if (data) { data.base64EncodedStringWithOptions(0).js; }
               |else { ''; }""".stripMargin
        Result.apply[String] {
            Sync.Unsafe.evalOrThrow(Process.Command("osascript", "-l", "JavaScript", "-e", script).text)
        }.toMaybe.flatMap { output =>
            val trimmed = output.trim
            Maybe.when(trimmed.nonEmpty)(java.util.Base64.getDecoder.decode(trimmed))
        }
    end macClipboardRead

    // ---- Linux clipboard via xclip (X11) or wl-paste (Wayland) ----

    private val isWayland: Boolean = env("WAYLAND_DISPLAY").nonEmpty

    private def linuxClipboardTypes()(using Frame, AllowUnsafe): Chunk[String] =
        val cmd =
            if isWayland then Process.Command("wl-paste", "--list-types")
            else Process.Command("xclip", "-selection", "clipboard", "-t", "TARGETS", "-o")
        Result.apply[String] {
            Sync.Unsafe.evalOrThrow(cmd.text)
        }.toMaybe.fold(Chunk.empty) { output =>
            Chunk.from(output.trim.split('\n').filter(_.nonEmpty))
        }
    end linuxClipboardTypes

    private def linuxClipboardRead(mimeType: String)(using Frame, AllowUnsafe): Maybe[Array[Byte]] =
        val cmd =
            if isWayland then Process.Command("wl-paste", "--type", mimeType)
            else Process.Command("xclip", "-selection", "clipboard", "-t", mimeType, "-o")
        Result.apply[Array[Byte]] {
            Sync.Unsafe.evalOrThrow(cmd.stream.map(_.readAllBytes()))
        }.toMaybe
    end linuxClipboardRead

    // ---- Windows clipboard via PowerShell ----

    private def windowsClipboardTypes()(using Frame, AllowUnsafe): Chunk[String] =
        val script =
            """$types = @();
              |try { $null = Get-Clipboard -Format Text -ErrorAction Stop; $types += 'text/plain' } catch {};
              |try { $null = Get-Clipboard -Format Image -ErrorAction Stop; $types += 'image/png' } catch {};
              |try { $null = Get-Clipboard -Format FileDropList -ErrorAction Stop; $types += 'text/uri-list' } catch {};
              |$types -join "`n"""".stripMargin
        Result.apply[String] {
            Sync.Unsafe.evalOrThrow(Process.Command("powershell", "-Command", script).text)
        }.toMaybe.fold(Chunk.empty) { output =>
            Chunk.from(output.trim.split('\n').filter(_.nonEmpty))
        }
    end windowsClipboardTypes

    private def windowsClipboardRead(mimeType: String)(using Frame, AllowUnsafe): Maybe[Array[Byte]] =
        val script = mimeType match
            case "text/plain" =>
                "Get-Clipboard -Format Text -Raw"
            case "image/png" =>
                """$img = Get-Clipboard -Format Image;
                  |$ms = New-Object System.IO.MemoryStream;
                  |$img.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png);
                  |[Convert]::ToBase64String($ms.ToArray())""".stripMargin
            case "text/uri-list" =>
                "(Get-Clipboard -Format FileDropList) -join \"`n\""
            case _ => ""
        if script.isEmpty then Absent
        else
            Result.apply[String] {
                Sync.Unsafe.evalOrThrow(Process.Command("powershell", "-Command", script).text)
            }.toMaybe.flatMap { output =>
                val trimmed = output.trim
                if trimmed.isEmpty then Absent
                else if mimeType == "image/png" then
                    Present(java.util.Base64.getDecoder.decode(trimmed))
                else
                    Present(trimmed.getBytes("UTF-8"))
                end if
            }
        end if
    end windowsClipboardRead

end PlatformCmd
