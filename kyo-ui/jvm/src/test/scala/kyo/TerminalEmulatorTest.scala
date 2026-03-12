package kyo

import kyo.Style.Size.*
import kyo.UI.*
import kyo.UI.internal.*
import kyo.internal.*
import scala.language.implicitConversions

class TerminalEmulatorTest extends Test:

    "frame captures rendered text" in run {
        for
            _ <- Signal.initRef(0)
        yield
            val emu = TerminalEmulator(
                span("Hello World"),
                cols = 40,
                rows = 5
            )
            val f = emu.frame
            assert(f.contains("Hello World"), s"frame:\n$f")
    }

    "screenshot produces valid PNG" in run {
        for
            _ <- Signal.initRef(0)
        yield
            val emu = TerminalEmulator(
                div(h1("Title"), p("Body text here.")),
                cols = 60,
                rows = 10
            )
            val path = java.io.File.createTempFile("tui-", ".png").getAbsolutePath
            emu.screenshot(path)
            val file = new java.io.File(path)
            val img  = javax.imageio.ImageIO.read(file)
            discard(file.delete())
            assert(img != null && img.getWidth > 0 && img.getHeight > 0)
    }

    "key events dispatched to focused element" in run {
        for
            keys <- Signal.initRef(Chunk.empty[String])
        yield
            val emu = TerminalEmulator(
                button("OK").onKeyDown(e => keys.getAndUpdate(_ :+ e.key.toString).unit),
                cols = 40,
                rows = 10
            )
            emu.tab()
            emu.key(UI.Keyboard.ArrowDown)
            emu.key(UI.Keyboard.ArrowUp)
            emu.waitForEffects()
            import AllowUnsafe.embrace.danger
            val k = Sync.Unsafe.evalOrThrow(keys.get)
            assert(k.size >= 2, s"Should have received key events: $k")
    }

    "mouse click triggers onClick" in run {
        for
            clicked <- Signal.initRef(false)
        yield
            val emu = TerminalEmulator(
                button("Click Me").onClick(clicked.set(true)),
                cols = 40,
                rows = 10
            )
            emu.click(5, 1)
            emu.waitForEffects()
            import AllowUnsafe.embrace.danger
            val c = Sync.Unsafe.evalOrThrow(clicked.get)
            assert(c, "Button should have been clicked")
    }

    "scroll events don't crash" in run {
        for
            _ <- Signal.initRef(0)
        yield
            val emu = TerminalEmulator(
                div(span("Scrollable content")),
                cols = 40,
                rows = 10
            )
            emu.tab()
            emu.scrollDown(5, 5)
            emu.scrollUp(5, 5)
            assert(true)
    }

    "paste dispatches to focused input" in run {
        for
            pasted <- Signal.initRef("")
        yield
            val emu = TerminalEmulator(
                input.value(pasted).placeholder("Paste here"),
                cols = 40,
                rows = 10
            )
            emu.tab()
            emu.paste("pasted text")
            emu.waitForEffects()
            import AllowUnsafe.embrace.danger
            val v = Sync.Unsafe.evalOrThrow(pasted.get)
            assert(v == "pasted text", s"Should have pasted text, got: '$v'")
    }

    "typeText dispatches character keys" in run {
        for
            text <- Signal.initRef("")
        yield
            val emu = TerminalEmulator(
                input.value(text),
                cols = 40,
                rows = 10
            )
            emu.tab()
            emu.typeText("abc")
            emu.waitForEffects()
            import AllowUnsafe.embrace.danger
            val v = Sync.Unsafe.evalOrThrow(text.get)
            assert(v == "abc", s"Should have typed 'abc', got: '$v'")
    }

    "reactive content updates after interaction" in run {
        for
            count <- Signal.initRef(0)
        yield
            val emu = TerminalEmulator(
                div(
                    span(count.map(c => s"Count: $c")),
                    button("+1").onClick(count.getAndUpdate(_ + 1).unit)
                ),
                cols = 40,
                rows = 10
            )
            val f0 = emu.frame
            assert(f0.contains("Count: 0"), s"Initial:\n$f0")

            // Click at a position inside the button area
            emu.click(2, 3)
            emu.waitForEffects()
            val f1 = emu.frame
            assert(f1.contains("Count: 1"), s"After click:\n$f1")
    }

    "counter demo renders buttons with text" in run {
        for
            count <- Signal.initRef(0)
        yield
            val emu = TerminalEmulator(
                div(
                    span(count.map(c => s"Count: $c")),
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    )
                ),
                cols = 40,
                rows = 10
            )
            val f = emu.frame
            assert(f.contains("-"), s"frame should show '-':\n$f")
            assert(f.contains("+"), s"frame should show '+':\n$f")
            assert(f.contains("Count: 0"), s"frame should show 'Count: 0':\n$f")
    }

    "clickOn finds button text" in run {
        for
            count <- Signal.initRef(0)
        yield
            val emu = TerminalEmulator(
                div(
                    span(count.map(c => s"Count: $c")),
                    nav(
                        button("-").onClick(count.getAndUpdate(_ - 1).unit),
                        button("+").onClick(count.getAndUpdate(_ + 1).unit)
                    )
                ),
                cols = 40,
                rows = 10
            )
            emu.clickOn("+")
            emu.waitForEffects()
            import AllowUnsafe.embrace.danger
            val c = Sync.Unsafe.evalOrThrow(count.get)
            assert(c == 1, s"Count should be 1 after clicking +, got: $c")
    }

end TerminalEmulatorTest
