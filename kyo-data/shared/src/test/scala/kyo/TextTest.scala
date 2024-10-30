package kyo

class TextTest extends Test:

    "empty" - {
        "empty Text is empty" in {
            assert(Text.empty.isEmpty)
            assert(Text.empty.length == 0)
        }

        "empty String as Text is empty" in {
            assert(Text("").isEmpty)
            assert(Text("").length == 0)
        }
    }

    "length" - {
        "simple Text" in {
            assert(Text("hello").length == 5)
        }

        "concatenated Text" in {
            assert((Text("hello") + " " + "world").length == 11)
        }

        "cut Text" in {
            assert(Text("hello").substring(1, 4).length == 3)
        }
    }

    "charAt" - {
        "simple Text" in {
            val text = Text("hello")
            assert(text.charAt(0) == 'h')
            assert(text.charAt(4) == 'o')
        }

        "concatenated Text" in {
            val text = Text("hello") + " " + "world"
            assert(text.charAt(0) == 'h')
            assert(text.charAt(5) == ' ')
            assert(text.charAt(10) == 'd')
        }

        "cut Text" in {
            val text = Text("hello").substring(1, 4)
            assert(text.charAt(0) == 'e')
            assert(text.charAt(2) == 'l')
        }

        "out of bounds" in {
            assertThrows[Throwable] {
                Text("hello").charAt(5)
            }
            assertThrows[Throwable] {
                Text("hello").charAt(-1)
            }
        }

        "charAt on concat boundary" in {
            val text = Text("hello") + Text("world")
            assert(text.charAt(4) == 'o')
            assert(text.charAt(5) == 'w')
        }
    }

    "substring" - {
        "simple Text" in {
            assert(Text("hello").substring(1, 4).toString == "ell")
        }

        "concatenated Text" in {
            val text = Text("hello") + " " + "world"
            text.substring(3, 8)
            assert(text.substring(3, 8).toString == "lo wo")
        }

        "cut Text" in {
            val text = Text("hello").substring(1, 4)
            assert(text.substring(1, 2).toString == "l")
        }

        "invalid ranges" in {
            assert(Result.catching[IllegalArgumentException](Text("hello").substring(4, 2)).isFail)
            assert(Result.catching[StringIndexOutOfBoundsException](Text("hello").substring(-1, 2).toString == "he").isFail)
            assert(Result.catching[StringIndexOutOfBoundsException](Text("hello").substring(3, 10).toString == "lo").isFail)
        }

        "nested cuts" in {
            val text = Text("hello world")
            val cut1 = text.substring(1, 9)
            val cut2 = cut1.substring(2, 6)
            assert(cut2.toString == "lo w")
        }

        "substring of concat" in {
            val text = Text("hello") + " " + "world"
            assert(text.substring(3, 8).toString == "lo wo")
        }

        "substring across concat boundary" in {
            val text = Text("hello") + Text("world")
            assert(text.substring(4, 7).toString == "owo")
        }
    }

    "+" - {
        "concatenate empty Texts" in {
            assert((Text.empty + Text.empty).isEmpty)
            assert((Text("") + Text("")).isEmpty)
        }

        "concatenate with empty Text" in {
            assert((Text.empty + Text("hello")).toString == "hello")
            assert((Text("hello") + Text.empty).toString == "hello")
        }

        "concatenate non-empty Texts" in {
            assert((Text("hello") + " " + "world").toString == "hello world")
        }

        "multiple concatenations" in {
            assert((Text("a") + "b" + "c" + "d").toString == "abcd")
        }

        "nested concatenations" in {
            val text = ((((Text("a") + "b") + "c") + "d") + "e") + "f"
            assert(text.toString == "abcdef")
            assert(text.length == 6)
            assert(text.charAt(3) == 'd')
        }
    }

    "trim" - {
        "no whitespace" in {
            assert(Text("hello").trim.toString == "hello")
        }

        "leading whitespace" in {
            assert(Text("  hello").trim.toString == "hello")
        }

        "trailing whitespace" in {
            assert(Text("hello  ").trim.toString == "hello")
        }

        "both ends whitespace" in {
            assert(Text("  hello  ").trim.toString == "hello")
        }

        "only whitespace" in {
            assert(Text("   ").trim.isEmpty)
        }

        "concatenated with whitespace" in {
            assert((Text("  hello  ") + "  world  ").trim.toString == "hello    world")
        }

        "trim on concat with internal whitespace" in {
            val text = Text("  hello  ") + Text("  world  ")
            assert(text.trim.toString == "hello    world")
        }
    }

    "contains" - {
        "empty substring" in {
            assert(Text("hello").contains(""))
            assert(Text("").contains(""))
        }

        "exact match" in {
            assert(Text("hello").contains("hello"))
        }

        "substring match" in {
            assert(Text("hello").contains("ell"))
        }

        "no match" in {
            assert(!Text("hello").contains("world"))
        }

        "concatenated Text" in {
            val text = Text("hello") + " " + "world"
            assert(text.contains("lo wo"))
        }
    }

    "startsWith" - {
        "empty prefix" in {
            assert(Text("hello").startsWith(""))
            assert(Text("").startsWith(""))
        }

        "exact match" in {
            assert(Text("hello").startsWith("hello"))
        }

        "partial match" in {
            assert(Text("hello").startsWith("hel"))
        }

        "no match" in {
            assert(!Text("hello").startsWith("ello"))
        }

        "prefix longer than text" in {
            assert(!Text("hi").startsWith("hello"))
        }
    }

    "endsWith" - {
        "empty suffix" in {
            assert(Text("hello").endsWith(""))
            assert(Text("").endsWith(""))
        }

        "exact match" in {
            assert(Text("hello").endsWith("hello"))
        }

        "partial match" in {
            assert(Text("hello").endsWith("llo"))
        }

        "no match" in {
            assert(!Text("hello").endsWith("hell"))
        }

        "suffix longer than text" in {
            assert(!Text("hi").endsWith("hello"))
        }
    }

    "indexOf" - {
        "empty substring" in {
            assert(Text("hello").indexOf("") == 0)
            assert(Text("").indexOf("") == 0)
        }

        "single occurrence" in {
            assert(Text("hello").indexOf("ell") == 1)
        }

        "multiple occurrences" in {
            assert(Text("hello hello").indexOf("hello") == 0)
        }

        "no occurrence" in {
            assert(Text("hello").indexOf("world") == -1)
        }

        "substring longer than text" in {
            assert(Text("hi").indexOf("hello") == -1)
        }

        "indexOf across concat boundary" in {
            val text = Text("hello") + Text("world")
            assert(text.indexOf("ow") == 4)
        }

        "indexOf with overlapping matches" in {
            val text = Text("ababab")
            assert(text.indexOf("abab") == 0)
        }
    }

    "lastIndexOf" - {
        "empty substring" in {
            assert(Text("hello").lastIndexOf("") == 5)
            assert(Text("").lastIndexOf("") == 0)
        }

        "single occurrence" in {
            assert(Text("hello").lastIndexOf("ell") == 1)
        }

        "multiple occurrences" in {
            assert(Text("hello hello").lastIndexOf("hello") == 6)
        }

        "no occurrence" in {
            assert(Text("hello").lastIndexOf("world") == -1)
        }

        "substring longer than text" in {
            assert(Text("hi").lastIndexOf("hello") == -1)
        }

        "lastIndexOf across concat boundary" in {
            val text = Text("hello") + Text("world")
            assert(text.lastIndexOf("o") == 6)
        }

        "lastIndexOf with overlapping matches" in {
            val text = Text("ababab")
            assert(text.lastIndexOf("abab") == 2)
        }
    }

    "split" - {
        "empty Text" in {
            assert(Text("").split(' ').isEmpty)
        }

        "no separator" in {
            assert(Text("hello").split(',').length == 1)
            assert(Text("hello").split(',').head.toString == "hello")
        }

        "single separator" in {
            val parts = Text("hello,world").split(',')
            assert(parts.length == 2)
            assert(parts(0).toString == "hello")
            assert(parts(1).toString == "world")
        }

        "multiple separators" in {
            val parts = Text("a,b,c,d").split(',')
            assert(parts.length == 4)
            assert(parts.map(_.toString) == Chunk("a", "b", "c", "d"))
        }

        "adjacent separators" in {
            val parts = Text("a,,b").split(',')
            assert(parts.length == 2)
            assert(parts.map(_.toString) == Chunk("a", "b"))
        }

        "leading/trailing separators" in {
            val parts = Text(",a,b,").split(',')
            assert(parts.length == 2)
            assert(parts.map(_.toString) == Chunk("a", "b"))
        }
    }

    "take/drop operations" - {
        val text = Text("hello world")

        "take" in {
            assert(text.take(5).toString == "hello")
            assert(text.take(0).isEmpty)
            assert(text.take(20).toString == "hello world")
            assert(text.take(-1).isEmpty)
        }

        "drop" in {
            assert(text.drop(6).toString == "world")
            assert(text.drop(0).toString == "hello world")
            assert(text.drop(20).isEmpty)
            assert(text.drop(-1).toString == "hello world")
        }

        "takeRight" in {
            assert(text.takeRight(5).toString == "world")
            assert(text.takeRight(0).isEmpty)
            assert(text.takeRight(20).toString == "hello world")
            assert(text.takeRight(-1).isEmpty)
        }

        "dropRight" in {
            assert(text.dropRight(6).toString == "hello")
            assert(text.dropRight(0).toString == "hello world")
            assert(text.dropRight(20).isEmpty)
            assert(text.dropRight(-1).toString == "hello world")
        }
    }

    "stripPrefix/stripSuffix" - {
        "stripPrefix" in {
            assert(Text("hello world").stripPrefix("hello ").toString == "world")
            assert(Text("hello world").stripPrefix("hi").toString == "hello world")
            assert(Text("").stripPrefix("hi").isEmpty)
            assert(Text("hello").stripPrefix("").toString == "hello")
        }

        "stripSuffix" in {
            assert(Text("hello world").stripSuffix(" world").toString == "hello")
            assert(Text("hello world").stripSuffix("bye").toString == "hello world")
            assert(Text("").stripSuffix("hi").isEmpty)
            assert(Text("hello").stripSuffix("").toString == "hello")
        }
    }

    "compareToIgnoreCase" - {
        "equal strings" in {
            assert(Text("hello").compareToIgnoreCase("HELLO") == 0)
            assert(Text("").compareToIgnoreCase("") == 0)
        }

        "different strings" in {
            assert(Text("abc").compareToIgnoreCase("def") < 0)
            assert(Text("DEF").compareToIgnoreCase("abc") > 0)
        }

        "different lengths" in {
            assert(Text("abc").compareToIgnoreCase("abcd") < 0)
            assert(Text("abcd").compareToIgnoreCase("abc") > 0)
        }

        "compare concat texts" in {
            val text1 = Text("hello") + Text("world")
            val text2 = Text("HELLO") + Text("WORLD")
            assert(text1.compareToIgnoreCase(text2) == 0)
        }

        "compare with different case across concat boundary" in {
            val text1 = Text("heLLo") + Text("woRLd")
            val text2 = Text("hello") + Text("WORLD")
            assert(text1.compareToIgnoreCase(text2) == 0)
        }
    }

    "head/tail" - {
        "head" in {
            assert(Text("hello").head == Maybe('h'))
            assert(Text("").head == Maybe.empty)
        }

        "tail" in {
            assert(Text("hello").tail.toString == "ello")
            assert(Text("h").tail.toString == "")
            assert(Text("").tail.toString == "")
        }
    }

    "span" - {
        "empty text" in {
            val (prefix, suffix) = Text("").span(_.isLetter)
            assert(prefix.toString == "")
            assert(suffix.toString == "")
        }

        "all matching" in {
            val (prefix, suffix) = Text("hello").span(_.isLetter)
            assert(prefix.toString == "hello")
            assert(suffix.toString == "")
        }

        "none matching" in {
            val (prefix, suffix) = Text("12345").span(_.isLetter)
            assert(prefix.toString == "")
            assert(suffix.toString == "12345")
        }

        "mixed content" in {
            val (prefix, suffix) = Text("hello123").span(_.isLetter)
            assert(prefix.toString == "hello")
            assert(suffix.toString == "123")
        }
    }

    "dropWhile" - {
        "empty text" in {
            assert(Text("").dropWhile(_.isWhitespace).isEmpty)
        }

        "all matching" in {
            assert(Text("   ").dropWhile(_.isWhitespace).isEmpty)
        }

        "none matching" in {
            assert(Text("hello").dropWhile(_.isWhitespace).toString == "hello")
        }

        "mixed content" in {
            assert(Text("   hello").dropWhile(_.isWhitespace).toString == "hello")
        }
    }

    "filterNot" - {
        "empty text" in {
            assert(Text("").filterNot(_.isWhitespace).isEmpty)
        }

        "all filtered" in {
            assert(Text("   ").filterNot(_.isWhitespace).isEmpty)
        }

        "none filtered" in {
            assert(Text("hello").filterNot(_.isDigit).toString == "hello")
        }

        "mixed content" in {
            assert(Text("h3ll0").filterNot(_.isDigit).toString == "hll")
        }
    }

    "count" - {
        "empty text" in {
            assert(Text("").count(_.isLetter) == 0)
        }

        "all matching" in {
            assert(Text("hello").count(_.isLetter) == 5)
        }

        "none matching" in {
            assert(Text("12345").count(_.isLetter) == 0)
        }

        "mixed content" in {
            assert(Text("h3ll0").count(_.isLetter) == 3)
        }
    }

    "show" - {
        "simple text" in {
            assert(Text("hello").show == "hello")
        }

        "empty text" in {
            assert(Text("").show == "")
        }

        "concatenated text" in {
            assert((Text("hello") + " " + "world").show == "hello world")
        }
    }

    "compact" - {
        "simple text" in {
            assert(Text("hello").compact.toString == "hello")
        }

        "concatenated text" in {
            assert((Text("hello") + " " + "world").compact.toString == "hello world")
        }

        "cut text" in {
            assert(Text("hello").substring(1, 4).compact.toString == "ell")
        }
    }

    "Unicode support" - {
        "surrogate pairs" in {
            val text = Text("Hello 🌍")
            assert(text.length == 8)
        }

        "substring with surrogate pairs" in {
            val text = Text("Hello 🌍 World")
            assert(text.substring(6, 8).toString == "🌍")
        }

        "indexOf with surrogate pairs" in {
            val text = Text("Hello 🌍 World 🌍")
            assert(text.indexOf("🌍") == 6)
            assert(text.lastIndexOf("🌍") == 15)
        }
    }

    "large texts" - {
        val largeText = Text("a" * 1000000)

        "substring on large text" in {
            assert(largeText.substring(500000, 500010).toString == "a" * 10)
        }

        "indexOf on large text" in {
            assert(largeText.indexOf("b") == -1)
            assert(largeText.indexOf("a" * 1000) == 0)
        }

        "concat large texts" in {
            val concat = largeText + largeText
            assert(concat.length == 2000000)
            assert(concat.charAt(1000000) == 'a')
        }
    }

    "is (equality)" - {
        "simple texts" in {
            assert(Text("hello").is(Text("hello")))
            assert(!Text("hello").is(Text("world")))
        }

        "empty texts" in {
            assert(Text("").is(Text("")))
            assert(!Text("").is(Text("a")))
        }

        "case sensitivity" in {
            assert(!Text("Hello").is(Text("hello")))
        }

        "concatenated texts" in {
            assert((Text("hello") + " world").is(Text("hello world")))
            assert(!(Text("hello") + "world").is(Text("helloworld ")))
        }

        "cut texts" in {
            assert(Text("hello world").substring(0, 5).is(Text("hello")))
            assert(!Text("hello world").substring(0, 5).is(Text("world")))
        }

        "mixed operations" in {
            val text1 = Text("hello") + " " + "world"
            val text2 = Text("hello world").substring(0, 11)
            assert(text1.is(text2))
        }

        "unicode support" in {
            assert(Text("Hello 🌍").is(Text("Hello 🌍")))
            assert(!Text("Hello 🌍").is(Text("Hello 🌎")))
        }

        "large texts" in {
            val large1 = Text("a" * 1000000)
            val large2 = Text("a" * 1000000)
            val large3 = Text("a" * 999999 + "b")
            assert(large1.is(large2))
            assert(!large1.is(large3))
        }
    }

end TextTest
