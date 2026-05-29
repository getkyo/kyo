package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Tests for ModifierParser covering doctest modifier parsing.
  *
  * These tests use inline strings only (no fixture files).
  */
class ModifierParserTest extends Test:

    private val dummyFile: kyo.Path = kyo.Path("test.md")

    "doctest:expect=fails-compile parses to Block.Expectation.FailsCompile" in run {
        Abort.run(ModifierParser.parse("scala doctest:expect=fails-compile", dummyFile, 1)).map {
            case Result.Success(mods) =>
                assert(mods.expect == Present(Block.Expectation.FailsCompile))
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "doctest:expect=runs parses to Block.Expectation.Runs" in run {
        Abort.run(ModifierParser.parse("scala doctest:expect=runs", dummyFile, 1)).map {
            case Result.Success(mods) =>
                assert(mods.expect == Present(Block.Expectation.Runs))
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "doctest:scope=inherited parses to Block.Visibility.Inherited" in run {
        Abort.run(ModifierParser.parse("scala doctest:scope=inherited", dummyFile, 1)).map {
            case Result.Success(mods) =>
                assert(mods.scope == Present(Block.Visibility.Inherited))
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "doctest:scope=env:tutorial parses to Block.Visibility.Env(\"tutorial\")" in run {
        Abort.run(ModifierParser.parse("scala doctest:scope=env:tutorial", dummyFile, 1)).map {
            case Result.Success(mods) =>
                assert(mods.scope == Present(Block.Visibility.Env("tutorial")))
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "doctest:platform=jvm,js parses to Set(JVM, JS)" in run {
        Abort.run(ModifierParser.parse("scala doctest:platform=jvm,js", dummyFile, 1)).map {
            case Result.Success(mods) =>
                assert(mods.platform == Present(Set(Block.Target.JVM, Block.Target.JS)))
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "doctest:scope=nested expect=warns platform=jvm parses all three" in run {
        Abort.run(ModifierParser.parse("scala doctest:scope=nested expect=warns platform=jvm", dummyFile, 1)).map {
            case Result.Success(mods) =>
                assert(mods.scope == Present(Block.Visibility.Nested))
                assert(mods.expect == Present(Block.Expectation.Warns))
                assert(mods.platform == Present(Set(Block.Target.JVM)))
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "doctest:setup normalises to Block.Visibility.Env(\"__doc__\")" in run {
        Abort.run(ModifierParser.parse("scala doctest:setup", dummyFile, 1)).map {
            case Result.Success(mods) =>
                assert(mods.scope == Present(Block.Visibility.Env("__doc__")))
            case Result.Failure(err) =>
                fail(s"unexpected parse error: $err")
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "unknown modifier key returns ParseError with line and key citation" in run {
        Abort.run(ModifierParser.parse("scala doctest:boguskey=x", dummyFile, 5)).map {
            case Result.Success(mods) =>
                fail(s"expected parse error but got: $mods")
            case Result.Failure(Doctest.Error.ParseError(f, l, msg)) =>
                // f is kyo.Path; compare via string representation
                assert(f.toString == dummyFile.toString, s"expected file $dummyFile but got $f")
                assert(l == 5)
                assert(msg.contains("boguskey"))
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

    "malformed expect value returns ParseError with line and value citation" in run {
        Abort.run(ModifierParser.parse("scala doctest:expect=bogus", dummyFile, 7)).map {
            case Result.Success(mods) =>
                fail(s"expected parse error but got: $mods")
            case Result.Failure(Doctest.Error.ParseError(f, l, msg)) =>
                assert(f.toString == dummyFile.toString, s"expected file $dummyFile but got $f")
                assert(l == 7)
                assert(msg.contains("bogus"))
            case Result.Panic(t) =>
                fail(s"unexpected panic: ${t.getMessage}")
        }
    }

end ModifierParserTest
