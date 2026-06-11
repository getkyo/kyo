package kyo

class ImageTest extends BaseBrowserTest:

    // Shared 16-byte fixture used across binary/base64 round-trip scenarios.
    private val fixtureBytes: Array[Byte]   = Array(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    private val fixtureBase64: String       = Base64.encode(Span.from(fixtureBytes))
    private val fixtureImage: Browser.Image = Browser.Image.fromBinary(fixtureBytes)

    "Image.fromBinary and Image.fromBase64 resolve via Browser.Image" in {
        // The implementation lives in `kyo.internal.Image`; users access it through the `Browser.Image`
        // export. Both names refer to the same opaque type.
        val direct: Browser.Image          = Browser.Image.fromBinary(fixtureBytes)
        val asInternal: kyo.internal.Image = direct
        Browser.Image.fromBase64(fixtureBase64) match
            case Result.Success(img) =>
                assert(img.base64 == fixtureBase64)
                assert(asInternal.base64 == img.base64)
            case other => fail(s"expected Success but got $other")
        end match
    }

    // ---- fromBase64 round-trip ----

    "Image.fromBase64(s).base64 == s for a fixture base64 string" in {
        Browser.Image.fromBase64(fixtureBase64) match
            case Result.Success(img) => assert(img.base64 == fixtureBase64)
            case other               => fail(s"expected Success but got $other")
    }

    "Image.fromBase64(s).base64 == s for an empty string" in {
        val emptyB64 = Base64.encode(Span.empty[Byte])
        Browser.Image.fromBase64(emptyB64) match
            case Result.Success(img) => assert(img.base64 == emptyB64)
            case other               => fail(s"expected Success but got $other")
    }

    // ---- malformed input; typed Result.Failure ----

    "Image.fromBase64 raises BrowserDecodingException on malformed input" in {
        // The malformed input emerges from `Image.fromBase64` as a `Result.Failure[IllegalArgumentException]`. Callers crossing
        // the wire boundary translate that to `BrowserDecodingException`; verified directly here so the contract is exercised
        // without needing a live browser tab.
        val malformed = "not_base64_!!!"
        val translated: Result[BrowserDecodingException, Browser.Image] =
            Browser.Image.fromBase64(malformed) match
                case Result.Success(img) => Result.succeed(img)
                case Result.Failure(err) => Result.fail(BrowserDecodingException("Image.fromBase64", err.getMessage))
                case Result.Panic(t)     => fail(s"unexpected panic: $t")
        translated match
            case Result.Failure(ex: BrowserDecodingException) => assert(ex.method == "Image.fromBase64")
            case other                                        => fail(s"expected Result.Failure(BrowserDecodingException) but got $other")
    }

    // ---- fromBinary round-trip ----

    "Image.fromBinary(bytes).binary is byte-identical to bytes" in {
        val img = Browser.Image.fromBinary(fixtureBytes)
        assert(img.binary.toArrayUnsafe.sameElements(fixtureBytes))
    }

    // ---- writeFileBinary(path: String) ----

    "Image.writeFileBinary(path: String) writes binary; re-reading yields identical bytes" in {
        Scope.run {
            Path.tempScoped("kyo-image-test", ".bin").map { tmp =>
                val pathStr = tmp.unsafe.show
                fixtureImage.writeFileBinary(pathStr).andThen {
                    Path(pathStr).readBytes.map { read =>
                        assert(read.toArrayUnsafe.sameElements(fixtureBytes))
                    }
                }
            }
        }
    }

    // ---- writeFileBinary(path: Path) ----

    "Image.writeFileBinary(path: Path) writes binary; re-reading yields identical bytes" in {
        Scope.run {
            Path.tempScoped("kyo-image-test", ".bin").map { tmp =>
                fixtureImage.writeFileBinary(tmp).andThen {
                    tmp.readBytes.map { read =>
                        assert(read.toArrayUnsafe.sameElements(fixtureBytes))
                    }
                }
            }
        }
    }

    // ---- writeFileBase64(path: String) ----

    "Image.writeFileBase64(path: String) writes base64; re-reading yields original base64 string" in {
        Scope.run {
            Path.tempScoped("kyo-image-test", ".b64").map { tmp =>
                val pathStr = tmp.unsafe.show
                fixtureImage.writeFileBase64(pathStr).andThen {
                    Path(pathStr).read.map { text =>
                        assert(text == fixtureBase64)
                    }
                }
            }
        }
    }

    // ---- writeFileBase64(path: Path) ----

    "Image.writeFileBase64(path: Path) writes base64; re-reading yields original base64 string" in {
        Scope.run {
            Path.tempScoped("kyo-image-test", ".b64").map { tmp =>
                fixtureImage.writeFileBase64(tmp).andThen {
                    tmp.read.map { text =>
                        assert(text == fixtureBase64)
                    }
                }
            }
        }
    }

    // ---- renderToConsole with iterm ----

    "Image.renderWith(ConsoleType.iterm) starts with ]1337;File=" in {
        // iTerm2 OSC 1337 protocol: ESC + ']' prefix, so the escape sequence is ESC + ]1337;File=
        val result = fixtureImage.renderWith(0, 0, Browser.Image.ConsoleType.iterm)
        assert(result.startsWith("]1337;File="))
    }

    // ---- renderToConsole with kitty ----

    "Image.renderWith(ConsoleType.kitty) starts with _G" in {
        // Kitty graphics protocol: ESC + '_G' APC sequence
        val result = fixtureImage.renderWith(0, 0, Browser.Image.ConsoleType.kitty)
        assert(result.startsWith("_G"))
    }

    // ---- renderToConsole with Absent ----

    "Image.ConsoleType.detect(Map.empty) returns Absent (no supported terminal)" in {
        val result = Browser.Image.ConsoleType.detect(Map.empty)
        assert(!result.isDefined)
    }

    // ---- value equality (derives CanEqual) ----

    "Image value equality: two instances built from identical bytes compare equal" in {
        // The `derives CanEqual` clause on `Image` enables `==` (and Scala 3 strictEquality) for `Image`
        // values. Two `Image` values built from the same byte payload must compare equal; the underlying
        // `Span[Byte]` already derives CanEqual and provides byte-wise equality.
        val left  = Browser.Image.fromBinary(fixtureBytes)
        val right = Browser.Image.fromBinary(fixtureBytes)
        assert(left == right)
        assert(left.hashCode == right.hashCode)
    }

    "Image value equality: instances with differing bytes do not compare equal" in {
        // Negative twin; confirms equality is not trivially-true / structural-only on the wrapper.
        val a     = Browser.Image.fromBinary(fixtureBytes)
        val other = Browser.Image.fromBinary(Array[Byte](99, 99, 99, 99))
        assert(a != other)
    }

    // ---- ConsoleType.detect env-detection ----

    "ConsoleType.detect with TERM_PROGRAM=iTerm.app yields iterm" in {
        val env    = Map("TERM_PROGRAM" -> "iTerm.app")
        val result = Browser.Image.ConsoleType.detect(env)
        assert(result == Present(Browser.Image.ConsoleType.iterm))
    }

    "ConsoleType.detect with KITTY_WINDOW_ID set and TERM=xterm-kitty yields kitty" in {
        val env    = Map("TERM" -> "xterm-kitty")
        val result = Browser.Image.ConsoleType.detect(env)
        assert(result == Present(Browser.Image.ConsoleType.kitty))
    }

    "ConsoleType.detect with neither TERM_PROGRAM nor kitty TERM yields Absent" in {
        val env    = Map("TERM_PROGRAM" -> "Apple_Terminal", "TERM" -> "xterm-256color")
        val result = Browser.Image.ConsoleType.detect(env)
        assert(result == Absent)
    }

end ImageTest
