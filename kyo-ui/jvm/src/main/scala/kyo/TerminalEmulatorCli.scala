package kyo

import kyo.UI.*
import kyo.internal.tui2.*
import scala.annotation.tailrec
import scala.language.implicitConversions

/** Persistent command-line driver for TerminalEmulator.
  *
  * Polls a command file for new commands. After each command, saves a screenshot and writes the frame text to an output file. Designed for
  * Claude to drive interactively across multiple bash calls.
  *
  * Protocol:
  *   1. Write command to {dir}/cmd
  *   2. Process executes it, writes frame to {dir}/output, saves {dir}/screenshot.png
  *   3. Process deletes {dir}/cmd and waits for next one
  *
  * Commands: key <name> [ctrl] [alt] [shift] — send key tab / shifttab / enter / space — shorthand keys type <text> — type characters click
  * <x> <y> — left click rightclick <x> <y> — right click release <x> <y> — release mouse mousemove <x> <y> — move mouse drag <x> <y> — left
  * drag clickon <text> — click on text scrollup <x> <y> — scroll up scrolldown <x> <y> — scroll down paste <text> — paste text wait [ms] —
  * wait for effects
  *
  * Args: --demo <name> --cols <n> --rows <n> --dir <path>
  */
object TerminalEmulatorCli:

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal

        val cols     = argInt(args, "--cols", 80)
        val rows     = argInt(args, "--rows", 24)
        val demoName = argStr(args, "--demo", "counter")
        val dir      = argStr(args, "--dir", "/tmp/tui-emu")

        val dirFile = new java.io.File(dir)
        dirFile.mkdirs()

        // Clean up stale files
        val cmdFile        = new java.io.File(dir, "cmd")
        val outputFile     = new java.io.File(dir, "output")
        val screenshotFile = new java.io.File(dir, "screenshot.png")
        val pidFile        = new java.io.File(dir, "pid")
        cmdFile.delete()
        outputFile.delete()

        val ui = demos.getOrElse(
            demoName, {
                java.lang.System.err.println(s"Unknown demo: $demoName. Available: ${demos.keys.mkString(", ")}")
                java.lang.System.exit(1)
                throw new RuntimeException("unreachable")
            }
        )()

        val emu = TerminalEmulator(ui, cols, rows)

        // Write PID so caller can check/kill
        val pw = new java.io.PrintWriter(pidFile)
        pw.println(ProcessHandle.current().pid())
        pw.close()

        // Initial screenshot + output
        var step = 0
        snapshot(emu, dir, step, outputFile, screenshotFile)
        java.lang.System.err.println(s"TerminalEmulator ready ($cols x $rows, demo=$demoName)")
        java.lang.System.err.println(s"  dir: $dir")
        java.lang.System.err.println(s"  Write commands to: ${cmdFile.getAbsolutePath}")

        // Poll loop
        @tailrec def loop(): Unit =
            if cmdFile.exists() then
                val command = readFile(cmdFile).trim
                cmdFile.delete()
                if command == "quit" then
                    pidFile.delete()
                    java.lang.System.err.println("Quit.")
                else
                    exec(emu, command)
                    step += 1
                    snapshot(emu, dir, step, outputFile, screenshotFile)
                    loop()
                end if
            else
                Thread.sleep(50)
                loop()
        loop()
    end main

    private def snapshot(
        emu: TerminalEmulator,
        dir: String,
        step: Int,
        outputFile: java.io.File,
        screenshotFile: java.io.File
    )(using Frame, AllowUnsafe): Unit =
        val frame = emu.frame
        emu.screenshot(screenshotFile.getAbsolutePath)
        // Also save numbered copy
        emu.screenshot(s"$dir/step-${f"$step%03d"}.png")
        val pw = new java.io.PrintWriter(outputFile)
        pw.println(frame)
        pw.close()
    end snapshot

    private def readFile(f: java.io.File): String =
        val src = scala.io.Source.fromFile(f)
        try src.mkString
        finally src.close()
    end readFile

    private def exec(emu: TerminalEmulator, line: String)(using Frame, AllowUnsafe): Unit =
        val parts = line.split("\\s+", 2)
        val cmd   = parts(0).toLowerCase
        val arg   = if parts.length > 1 then parts(1) else ""
        try
            cmd match
                case "key" =>
                    val keyParts = arg.split("\\s+")
                    val keyName  = keyParts(0)
                    val mods     = keyParts.drop(1).map(_.toLowerCase).toSet
                    val k        = parseKey(keyName)
                    emu.key(k, ctrl = mods.contains("ctrl"), alt = mods.contains("alt"), shift = mods.contains("shift"))

                case "tab"      => emu.tab()
                case "shifttab" => emu.shiftTab()
                case "enter"    => emu.enter()
                case "space"    => emu.space()
                case "type"     => emu.typeText(arg)

                case "click" =>
                    val xy = arg.split("\\s+")
                    emu.click(xy(0).toInt, xy(1).toInt)

                case "rightclick" =>
                    val xy = arg.split("\\s+")
                    emu.rightClick(xy(0).toInt, xy(1).toInt)

                case "release" =>
                    val xy = arg.split("\\s+")
                    emu.release(xy(0).toInt, xy(1).toInt)

                case "mousemove" =>
                    val xy = arg.split("\\s+")
                    emu.mouseMove(xy(0).toInt, xy(1).toInt)

                case "drag" =>
                    val xy = arg.split("\\s+")
                    emu.drag(xy(0).toInt, xy(1).toInt)

                case "clickon" => emu.clickOn(arg)

                case "scrollup" =>
                    val xy = arg.split("\\s+")
                    emu.scrollUp(xy(0).toInt, xy(1).toInt)

                case "scrolldown" =>
                    val xy = arg.split("\\s+")
                    emu.scrollDown(xy(0).toInt, xy(1).toInt)

                case "paste" => emu.paste(arg)

                case "wait" =>
                    val ms = if arg.nonEmpty then arg.toInt else 100
                    emu.waitForEffects(ms)

                case _ =>
                    java.lang.System.err.println(s"Unknown command: $cmd")
        catch
            case e: Exception =>
                java.lang.System.err.println(s"Error [${e.getClass.getSimpleName}]: ${e.getMessage}")
        end try
    end exec

    private def parseKey(name: String): UI.Keyboard =
        name.toLowerCase match
            case "enter"                => UI.Keyboard.Enter
            case "tab"                  => UI.Keyboard.Tab
            case "escape" | "esc"       => UI.Keyboard.Escape
            case "backspace"            => UI.Keyboard.Backspace
            case "delete"               => UI.Keyboard.Delete
            case "space"                => UI.Keyboard.Space
            case "arrowup" | "up"       => UI.Keyboard.ArrowUp
            case "arrowdown" | "down"   => UI.Keyboard.ArrowDown
            case "arrowleft" | "left"   => UI.Keyboard.ArrowLeft
            case "arrowright" | "right" => UI.Keyboard.ArrowRight
            case "home"                 => UI.Keyboard.Home
            case "end"                  => UI.Keyboard.End
            case "pageup"               => UI.Keyboard.PageUp
            case "pagedown"             => UI.Keyboard.PageDown
            case "insert"               => UI.Keyboard.Insert
            case s if s.startsWith("f") && s.drop(1).forall(_.isDigit) =>
                s.drop(1).toInt match
                    case 1  => UI.Keyboard.F1
                    case 2  => UI.Keyboard.F2
                    case 3  => UI.Keyboard.F3
                    case 4  => UI.Keyboard.F4
                    case 5  => UI.Keyboard.F5
                    case 6  => UI.Keyboard.F6
                    case 7  => UI.Keyboard.F7
                    case 8  => UI.Keyboard.F8
                    case 9  => UI.Keyboard.F9
                    case 10 => UI.Keyboard.F10
                    case 11 => UI.Keyboard.F11
                    case 12 => UI.Keyboard.F12
                    case n  => UI.Keyboard.Unknown(s"F$n")
            case s if s.length == 1 => UI.Keyboard.Char(s.charAt(0))
            case s                  => UI.Keyboard.Unknown(s)
    end parseKey

    private def argStr(args: Array[String], flag: String, default: String): String =
        @tailrec def loop(i: Int): String =
            if i >= args.length - 1 then default
            else if args(i) == flag then args(i + 1)
            else loop(i + 1)
        loop(0)
    end argStr

    private def argInt(args: Array[String], flag: String, default: Int): Int =
        argStr(args, flag, default.toString).toInt

    // ---- Built-in demo UIs ----

    private val demos: Map[String, () => UI] =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        Map(
            "counter" -> { () =>
                val count = Sync.Unsafe.evalOrThrow(Signal.initRef(0))
                div(
                    span(count.map(c => s"Count: $c")),
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    )
                )
            },
            "form" -> { () =>
                val name  = Sync.Unsafe.evalOrThrow(Signal.initRef(""))
                val email = Sync.Unsafe.evalOrThrow(Signal.initRef(""))
                form(
                    div(label("Name:"), input.value(name).placeholder("Enter name")),
                    div(label("Email:"), input.value(email).placeholder("Enter email")),
                    button("Submit"),
                    span(name.map(n => s"Name=$n"))
                )
            },
            "text" -> { () =>
                div(
                    h1("Heading 1"),
                    h2("Heading 2"),
                    p("A paragraph of text."),
                    span("Inline span text.")
                )
            }
        )
    end demos

end TerminalEmulatorCli
