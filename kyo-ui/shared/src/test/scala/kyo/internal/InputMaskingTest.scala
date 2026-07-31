package kyo.internal

import kyo.*

/** Covers the pure filter and mask logic on every platform.
  *
  * What is deliberately NOT covered here: the DOM wiring in `DomBackend` (`setupInputMasking`, `setValue`,
  * `setFilteredAt`, `dispatchInput`, the `compositionend` listener). The browser harness serves the server-push
  * transport, so no test in this repo loads the Scala.js backend; those methods are thin shims over the logic below,
  * and the parts that can be observed from a browser are covered by `InputMaskingScenarioItTest` against the
  * JavaScript mirror, which `kyo.InputMaskingJsParityTest` holds to the same case table.
  */
class InputMaskingTest extends kyo.test.Test[Any]:

    private def m(mask: String) = InputMasking.parseMask(mask)

    "filterStr" - {

        "digits" - {
            "keeps digits" in {
                assert(InputMasking.filterStr("digits", "123", "") == "123")
            }
            "drops letters and symbols" in {
                assert(InputMasking.filterStr("digits", "a1b2-3", "") == "123")
            }
            "empty input yields empty" in {
                assert(InputMasking.filterStr("digits", "", "") == "")
            }
            "drops a separator" in {
                assert(InputMasking.filterStr("digits", "1.5", "") == "15")
            }
        }

        "decimal" - {
            "keeps digits" in {
                assert(InputMasking.filterStr("decimal", "123", "") == "123")
            }
            "keeps the first dot" in {
                assert(InputMasking.filterStr("decimal", "1.5", "") == "1.5")
            }
            "keeps the first comma" in {
                assert(InputMasking.filterStr("decimal", "1,5", "") == "1,5")
            }
            "drops a second separator within the same input" in {
                assert(InputMasking.filterStr("decimal", "1.5.2", "") == "1.52")
            }
            "drops a separator when the field already holds one" in {
                assert(InputMasking.filterStr("decimal", ".5", "1.0") == "5")
            }
            "treats a comma in the field as the separator too" in {
                assert(InputMasking.filterStr("decimal", ".5", "1,0") == "5")
            }
            "drops a leading sign, so negatives are not expressible" in {
                assert(InputMasking.filterStr("decimal", "-1.5", "") == "1.5")
            }
            "drops letters" in {
                assert(InputMasking.filterStr("decimal", "1a.5b", "") == "1.5")
            }
        }

        "explicit character set" - {
            "keeps characters in the set" in {
                assert(InputMasking.filterStr("chars:abc", "cab", "") == "cab")
            }
            "drops characters outside the set" in {
                assert(InputMasking.filterStr("chars:abc", "axbycz", "") == "abc")
            }
            "an empty set drops everything" in {
                assert(InputMasking.filterStr("chars:", "abc", "") == "")
            }
            "an unrecognized wire value admits everything" in {
                // Fail open: a page cached from an older build must stay usable, not become impossible to type into.
                assert(InputMasking.filterStr("int", "a1.", "") == "a1.")
                assert(InputMasking.filterStr("nonsense", "a1.", "") == "a1.")
            }
            "a set spelling a keyword is reachable through the prefix" in {
                // The regression the typed InputFilter fixes: the old wire format read "int" as the
                // digits-only keyword, so an allowed set of i, n and t could not be expressed at all.
                assert(InputMasking.filterStr("chars:int", "int", "") == "int")
                assert(InputMasking.filterStr("chars:int", "1", "") == "")
            }
        }
    }

    "filterWire round-trips through filterStr" - {
        "Digits" in {
            assert(InputMasking.filterWire(UI.InputFilter.Digits) == "digits")
            assert(InputMasking.filterStr(InputMasking.filterWire(UI.InputFilter.Digits), "a1", "") == "1")
        }
        "Decimal" in {
            assert(InputMasking.filterWire(UI.InputFilter.Decimal) == "decimal")
            assert(InputMasking.filterStr(InputMasking.filterWire(UI.InputFilter.Decimal), "a1.5", "") == "1.5")
        }
        "Allowed carries an arbitrary set, keywords included" in {
            val wire = InputMasking.filterWire(UI.InputFilter.Allowed("int"))
            assert(wire == "chars:int")
            assert(InputMasking.filterStr(wire, "int1", "") == "int")
        }
        "Allowed carries a set that itself looks like the prefix" in {
            val wire = InputMasking.filterWire(UI.InputFilter.Allowed("chars:"))
            assert(InputMasking.filterStr(wire, "chars:x", "") == "chars:")
        }
        "Allowed with an empty set drops everything" in {
            assert(InputMasking.filterStr(InputMasking.filterWire(UI.InputFilter.Allowed("")), "abc", "") == "")
        }
    }

    "maskClassAt" - {
        "index zero skips a leading literal" in {
            assert(InputMasking.maskClassAt(m("(999) 999"), 0) == Present('9'))
        }
        "counts only input positions, not literals" in {
            assert(InputMasking.maskClassAt(m("(999) 999"), 3) == Present('9'))
        }
        "reports the class of a mixed mask" in {
            assert(InputMasking.maskClassAt(m("9a*"), 0) == Present('9'))
            assert(InputMasking.maskClassAt(m("9a*"), 1) == Present('a'))
            assert(InputMasking.maskClassAt(m("9a*"), 2) == Present('*'))
        }
        "past capacity is Absent" in {
            assert(InputMasking.maskClassAt(m("999"), 3) == Absent)
        }
        "a mask of only literals has no input position" in {
            assert(InputMasking.maskClassAt(m("()-"), 0) == Absent)
        }
        "an empty mask has no input position" in {
            assert(InputMasking.maskClassAt(m(""), 0) == Absent)
        }
    }

    "parseMask" - {
        "class tokens and literals" in {
            assert(m("(9a*)") == Chunk(
                InputMasking.MaskToken.Literal('('),
                InputMasking.MaskToken.Class('9'),
                InputMasking.MaskToken.Class('a'),
                InputMasking.MaskToken.Class('*'),
                InputMasking.MaskToken.Literal(')')
            ))
        }
        "a backslash escapes a class character into a literal" in {
            assert(m("\\9") == Chunk(InputMasking.MaskToken.Literal('9')))
            assert(m("\\a") == Chunk(InputMasking.MaskToken.Literal('a')))
            assert(m("\\*") == Chunk(InputMasking.MaskToken.Literal('*')))
        }
        "a backslash escapes itself" in {
            assert(m("\\\\") == Chunk(InputMasking.MaskToken.Literal('\\')))
        }
        "a trailing lone backslash is a literal" in {
            assert(m("9\\") == Chunk(InputMasking.MaskToken.Class('9'), InputMasking.MaskToken.Literal('\\')))
        }
        "an empty pattern has no positions" in {
            assert(m("") == Chunk.empty)
        }
        "an escaped class character is not an input position" in {
            // The regression the escape fixes: "+49 999" used to open input positions inside the country code.
            assert(InputMasking.maskClassAt(m("+4\\9 999"), 0) == Present('9'))
            assert(InputMasking.maskFormat(m("+4\\9 999"), "123") == "+49 123")
        }
    }

    "maskOk" - {
        "digit class" in {
            assert(InputMasking.maskOk('9', '0'))
            assert(InputMasking.maskOk('9', '9'))
            assert(!InputMasking.maskOk('9', 'a'))
            assert(!InputMasking.maskOk('9', ' '))
        }
        "letter class" in {
            assert(InputMasking.maskOk('a', 'a'))
            assert(InputMasking.maskOk('a', 'Z'))
            assert(!InputMasking.maskOk('a', '0'))
            assert(!InputMasking.maskOk('a', '_'))
        }
        "letter class is ASCII only" in {
            // Pins the current behavior: accented and non-Latin letters are rejected.
            assert(!InputMasking.maskOk('a', 'ü'))
            assert(!InputMasking.maskOk('*', 'ü'))
        }
        "alphanumeric class" in {
            assert(InputMasking.maskOk('*', '0'))
            assert(InputMasking.maskOk('*', 'a'))
            assert(InputMasking.maskOk('*', 'Z'))
            assert(!InputMasking.maskOk('*', '-'))
        }
    }

    "maskFormat" - {
        "empty raw yields empty" in {
            assert(InputMasking.maskFormat(m("(999) 999"), "") == "")
        }
        "a leading literal appears with the first raw character" in {
            assert(InputMasking.maskFormat(m("(999) 999"), "1") == "(1")
        }
        "partial raw stops without trailing literals" in {
            assert(InputMasking.maskFormat(m("(999) 999"), "123") == "(123")
        }
        "literals between positions are inserted" in {
            assert(InputMasking.maskFormat(m("(999) 999"), "1234") == "(123) 4")
        }
        "raw filling every position omits a closing literal" in {
            assert(InputMasking.maskFormat(m("999-"), "123") == "123")
        }
        "raw beyond capacity is ignored" in {
            assert(InputMasking.maskFormat(m("999"), "123456") == "123")
        }
        "an empty mask yields empty" in {
            assert(InputMasking.maskFormat(m(""), "123") == "")
        }
    }

    "maskRaw" - {
        "literals are dropped" in {
            assert(InputMasking.maskRaw(m("(999) 999"), "(123) 456") == "123456")
        }
        "a partial value reduces to what was entered" in {
            assert(InputMasking.maskRaw(m("(999) 999"), "(12") == "12")
        }
        "a character not matching a literal position is kept as content" in {
            // The value is out of step with the mask; keeping the character preserves what the user typed.
            assert(InputMasking.maskRaw(m("(999"), "1234") == "1234")
        }
        "a value longer than the mask is truncated" in {
            assert(InputMasking.maskRaw(m("999"), "123456") == "123")
        }
        "an empty value yields empty" in {
            assert(InputMasking.maskRaw(m("(999) 999"), "") == "")
        }
    }

    "typing constraints are offered only where they work" - {
        "a text input accepts them" in {
            val e = UI.input.inputFilter(UI.InputFilter.Digits).inputMask("(999) 999")
            assert(e.inputFilter == Present(UI.InputFilter.Digits))
            assert(e.inputMask == Present("(999) 999"))
        }
        "a number input does not accept a filter" in {
            // NumberInput deliberately omits ConstrainedInput: it constrains its content through type,
            // min, max and step, its value reads empty while the content is not a valid number, and a
            // mask's own literals are never valid there.
            typeCheckFailure("""
                import kyo.*
                UI.numberInput.inputFilter(UI.InputFilter.Digits)
            """)
        }
        "a number input does not accept a mask" in {
            typeCheckFailure("""
                import kyo.*
                UI.numberInput.inputMask("(999) 999")
            """)
        }
    }

    "a rendered value carries the mask" - {
        // A mask is a display format, so it has to hold for values the client never sees typed: a bound SignalRef,
        // a server-side transform, the initial render. Formatting at render time is what makes that true in both
        // transports and on both the morph and the replace path.
        def render(ui: UI)(using Frame) = HtmlRenderer.render(ui, Seq.empty)

        "an unformatted value is formatted" in {
            render(UI.input.value("1234567890").inputMask("(999) 999-9999")).map { s =>
                assert(s.contains("""value="(123) 456-7890""""))
            }
        }
        "an already formatted value is unchanged, so a keystroke echo compares equal" in {
            render(UI.input.value("(123) 456").inputMask("(999) 999")).map { s =>
                assert(s.contains("""value="(123) 456""""))
            }
        }
        "a partial value renders without trailing literals" in {
            render(UI.input.value("12").inputMask("(999) 999")).map { s =>
                assert(s.contains("""value="(12""""))
            }
        }
        "characters the mask cannot hold are dropped" in {
            render(UI.input.value("12345678901234").inputMask("(999) 999")).map { s =>
                assert(s.contains("""value="(123) 456""""))
            }
        }
        "an escaped literal is not filled from the value" in {
            render(UI.telInput.value("123").inputMask("+4\\9 999")).map { s =>
                assert(s.contains("""value="+49 123""""))
            }
        }
        "a field without a mask keeps its value verbatim" in {
            render(UI.input.value("1234567890")).map { s =>
                assert(s.contains("""value="1234567890""""))
            }
        }
        "a textarea formats its content, which is not an attribute" in {
            render(UI.textarea.value("123456").inputMask("999-999")).map { s =>
                assert(s.contains(">123-456<"))
            }
        }
    }

    "the parity table agrees with the Scala implementation" in {
        // The table is shared with InputMaskingJsParityTest, which drives it against the JavaScript mirror.
        // Checking it here too is what makes a parity failure readable: if only the JavaScript suite fails,
        // the mirror has drifted; if both fail, the expectation itself is wrong.
        val wrong = InputMaskingTest.parityCases.filter(c => InputMaskingTest.runScala(c.call) != c.expected)
        assert(wrong.isEmpty, wrong.map(c => s"${c.call} yielded ${InputMaskingTest.runScala(c.call)}").mkString("; "))
    }

    "maskRaw inverts maskFormat for class-valid input" in {
        val cases = Seq(
            "(999) 999-9999" -> "1234567890",
            "999-999"        -> "123456",
            "aa-99"          -> "ab12",
            "***"            -> "a1Z",
            "99"             -> "",
            "99"             -> "4"
        )
        cases.foreach { (mask, raw) =>
            val tokens    = m(mask)
            val formatted = InputMasking.maskFormat(tokens, raw)
            assert(
                InputMasking.maskRaw(tokens, formatted) == raw,
                s"mask=$mask raw=$raw formatted=$formatted"
            )
        }
        succeed
    }
end InputMaskingTest

/** The case table both implementations of the filter and mask logic are held to.
  *
  * `InputMaskingTest` runs it against [[InputMasking]], `kyo.InputMaskingJsParityTest` against the JavaScript mirror
  * in `HtmlRenderer.inputMaskJs`. Adding a row therefore covers both transports at once, which is the coupling the
  * two hand-written implementations otherwise lack.
  */
object InputMaskingTest:

    /** A call into the logic, in the one form both implementations understand. */
    enum Call derives CanEqual:
        case Filter(pat: String, str: String, cur: String)
        case ClassAt(mask: String, idx: Int)
        case Ok(cls: Char, ch: Char)
        case Format(mask: String, raw: String)
        case Raw(mask: String, value: String)
        case Normalize(mask: String, value: String)
    end Call

    /** A call and the single result both implementations must produce.
      *
      * Results are compared as strings because that is the only shape the browser bridge returns: an absent mask
      * position is the empty string, and `maskOk` reports `"true"` or `"false"`.
      */
    final case class Case(call: Call, expected: String) derives CanEqual

    val parityCases: Chunk[Case] = Chunk(
        Case(Call.Filter("digits", "a1b2-3", ""), "123"),
        Case(Call.Filter("digits", "1.5", ""), "15"),
        Case(Call.Filter("digits", "", ""), ""),
        Case(Call.Filter("decimal", "1.5", ""), "1.5"),
        Case(Call.Filter("decimal", "1,5", ""), "1,5"),
        Case(Call.Filter("decimal", "1.5.2", ""), "1.52"),
        Case(Call.Filter("decimal", ".5", "1.0"), "5"),
        Case(Call.Filter("decimal", ".5", "1,0"), "5"),
        Case(Call.Filter("decimal", "-1.5", ""), "1.5"),
        Case(Call.Filter("decimal", "1a.5b", ""), "1.5"),
        Case(Call.Filter("chars:abc", "axbycz", ""), "abc"),
        Case(Call.Filter("chars:", "abc", ""), ""),
        Case(Call.Filter("chars:int", "int1", ""), "int"),
        Case(Call.Filter("chars:chars:", "chars:x", ""), "chars:"),
        Case(Call.Filter("int", "a1.", ""), "a1."),
        Case(Call.Filter("nonsense", "a1.", ""), "a1."),
        Case(Call.ClassAt("(999) 999", 0), "9"),
        Case(Call.ClassAt("(999) 999", 3), "9"),
        Case(Call.ClassAt("9a*", 1), "a"),
        Case(Call.ClassAt("9a*", 2), "*"),
        Case(Call.ClassAt("999", 3), ""),
        Case(Call.ClassAt("()-", 0), ""),
        Case(Call.ClassAt("", 0), ""),
        Case(Call.ClassAt("+4\\9 999", 0), "9"),
        Case(Call.Ok('9', '0'), "true"),
        Case(Call.Ok('9', 'a'), "false"),
        Case(Call.Ok('9', ' '), "false"),
        Case(Call.Ok('a', 'Z'), "true"),
        Case(Call.Ok('a', '0'), "false"),
        Case(Call.Ok('a', 'ü'), "false"),
        Case(Call.Ok('*', 'a'), "true"),
        Case(Call.Ok('*', '-'), "false"),
        Case(Call.Format("(999) 999", ""), ""),
        Case(Call.Format("(999) 999", "1"), "(1"),
        Case(Call.Format("(999) 999", "1234"), "(123) 4"),
        Case(Call.Format("999-", "123"), "123"),
        Case(Call.Format("999", "123456"), "123"),
        Case(Call.Format("", "123"), ""),
        Case(Call.Format("+4\\9 999", "123"), "+49 123"),
        Case(Call.Raw("(999) 999", "(123) 456"), "123456"),
        Case(Call.Raw("(999) 999", "(12"), "12"),
        Case(Call.Raw("(999", "1234"), "1234"),
        Case(Call.Raw("999", "123456"), "123"),
        Case(Call.Raw("(999) 999", ""), ""),
        Case(Call.Normalize("(999) 999", "123456"), "(123) 456"),
        Case(Call.Normalize("(999) 999", "(123) 456"), "(123) 456"),
        Case(Call.Normalize("(999) 999", "12"), "(12"),
        Case(Call.Normalize("(999) 999", "1234567890"), "(123) 456"),
        Case(Call.Normalize("+4\\9 999", "123"), "+49 123"),
        Case(Call.Normalize("(999) 999", ""), "")
    )

    /** Runs a call against the Scala implementation. */
    def runScala(call: Call): String =
        call match
            case Call.Filter(pat, str, cur)  => InputMasking.filterStr(pat, str, cur)
            case Call.ClassAt(mask, idx)     => InputMasking.maskClassAt(InputMasking.parseMask(mask), idx).fold("")(_.toString)
            case Call.Ok(cls, ch)            => InputMasking.maskOk(cls, ch).toString
            case Call.Format(mask, raw)      => InputMasking.maskFormat(InputMasking.parseMask(mask), raw)
            case Call.Raw(mask, value)       => InputMasking.maskRaw(InputMasking.parseMask(mask), value)
            case Call.Normalize(mask, value) => InputMasking.maskNormalize(mask, value)

    /** The JavaScript expression running the same call against the mirror, yielding the same string. */
    def runJs(call: Call): String =
        def q(s: String) = "\"" + HtmlRenderer.jsStr(s) + "\""
        call match
            case Call.Filter(pat, str, cur)  => s"kyoFilterStr(${q(pat)},${q(str)},${q(cur)})"
            case Call.ClassAt(mask, idx)     => s"(kyoMaskClassAt(kyoMaskParse(${q(mask)}),$idx)||\"\")"
            case Call.Ok(cls, ch)            => s"String(kyoMaskOk(${q(cls.toString)},${q(ch.toString)}))"
            case Call.Format(mask, raw)      => s"kyoMaskFormat(kyoMaskParse(${q(mask)}),${q(raw)})"
            case Call.Raw(mask, value)       => s"kyoMaskRaw(kyoMaskParse(${q(mask)}),${q(value)})"
            case Call.Normalize(mask, value) => s"kyoMaskNormalize(${q(mask)},${q(value)})"
        end match
    end runJs

end InputMaskingTest
