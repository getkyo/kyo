package kyo.doctest

import kyo.*
import kyo.doctest.internal.Block

class PublicAPITest extends DoctestTest:

    // Test 1: Doctest.Config.apply accepts the correct field types and constructs without throwing.
    "Doctest.Config.apply accepts Chunk[Path] sources, Chunk[Path] classpath, Chunk[String] scalaOpts, Path cache, Int parallel" in {
        val src  = Chunk(Path("README.md"))
        val cp   = Chunk(Path("target/scala-3/classes"))
        val opts = Chunk("-release", "17")
        val dir  = Path("target/doctest-cache")
        val cfg = Doctest.Config(
            sources = src,
            classpath = cp,
            scalaOpts = opts,
            cache = dir,
            parallel = 4
        )
        assert(cfg.sources == src)
        assert(cfg.classpath == cp)
        assert(cfg.scalaOpts == opts)
        assert(cfg.cache == dir)
        assert(cfg.parallel == 4)
    }

    // Test 2: Doctest.Config.parallel is a required Int field with no default.
    "Doctest.Config.parallel is a required Int field with no default" in {
        val cfg = Doctest.Config(
            sources = Chunk.empty,
            classpath = Chunk.empty,
            scalaOpts = Chunk.empty,
            cache = Path("target/doctest-cache"),
            parallel = 8
        )
        assert(cfg.parallel == 8)
    }

    // Test 3: Doctest.Report exposes totalBlocks, cacheHits, compiled, warnings, failures with declared types.
    "Doctest.Report exposes totalBlocks/cacheHits/compiled/warnings/failures with declared types" in {
        val failure = Doctest.Failure(
            file = Path("README.md"),
            line = 10,
            message = "type error"
        )
        val report: Doctest.Report = Doctest.Report(
            totalBlocks = 3,
            cacheHits = 1,
            compiled = 2,
            warnings = 0,
            failures = Chunk(failure)
        )
        val _totalBlocks: Int                 = report.totalBlocks
        val _cacheHits: Int                   = report.cacheHits
        val _compiled: Int                    = report.compiled
        val _warnings: Int                    = report.warnings
        val _failures: Chunk[Doctest.Failure] = report.failures
        assert(_totalBlocks == 3)
        assert(_cacheHits == 1)
        assert(_compiled == 2)
        assert(_warnings == 0)
        assert(_failures.size == 1)
        // Verify Failure fields
        assert(failure.file == Path("README.md"))
        assert(failure.line == 10)
        assert(failure.message == "type error")
    }

    // Test 4: Doctest.Error enum cases all instantiate with stated payloads.
    "Doctest.Error enum cases DriverInitFailed, SourceNotFound, CacheCorrupt, ParseError, NoSourcesConfigured all instantiate" in {
        val cause             = new RuntimeException("init failed")
        val e1: Doctest.Error = Doctest.Error.DriverInitFailed(cause)
        val e2: Doctest.Error = Doctest.Error.SourceNotFound(Path("missing.md"))
        val e3: Doctest.Error = Doctest.Error.CacheCorrupt(Path("target/cache/abc.ok"), cause)
        val e4: Doctest.Error = Doctest.Error.ParseError(Path("README.md"), line = 42, message = "unexpected token")
        val e5: Doctest.Error = Doctest.Error.NoSourcesConfigured
        e1 match
            case Doctest.Error.DriverInitFailed(c) => assert(c.eq(cause))
            case _                                 => fail("wrong case")
        e2 match
            case Doctest.Error.SourceNotFound(p) => assert(p == Path("missing.md"))
            case _                               => fail("wrong case")
        e3 match
            case Doctest.Error.CacheCorrupt(p, c) =>
                assert(p == Path("target/cache/abc.ok"))
                assert(c.eq(cause))
            case _ => fail("wrong case")
        end match
        e4 match
            case Doctest.Error.ParseError(f, l, m) =>
                assert(f == Path("README.md"))
                assert(l == 42)
                assert(m == "unexpected token")
            case _ => fail("wrong case")
        end match
        e5 match
            case Doctest.Error.NoSourcesConfigured => ()
            case _                                 => fail("wrong case")
    }

    // Test 5: Block case class (in kyo.doctest.internal) round-trips via copy with each field replaced one-at-a-time.
    "Block case class round-trips via copy with each field replaced one-at-a-time" in {
        val original = Block(
            file = Path("README.md"),
            lineStart = 5,
            lineEnd = 10,
            body = "val x = 1",
            visibility = Block.Visibility.Isolated,
            expect = Block.Expectation.Compiles,
            platform = Set(Block.Target.JVM, Block.Target.JS, Block.Target.Native),
            carrier = Block.Carrier.Visible
        )
        val newFile  = original.copy(file = Path("GUIDE.md"))
        val newStart = original.copy(lineStart = 20)
        val newEnd   = original.copy(lineEnd = 25)
        val newBody  = original.copy(body = "val y = 2")
        val newScope = original.copy(visibility = Block.Visibility.Inherited)
        val newExp   = original.copy(expect = Block.Expectation.Runs)
        val newPlat  = original.copy(platform = Set(Block.Target.JVM))
        val newCarr  = original.copy(carrier = Block.Carrier.Hidden)
        assert(newFile.file == Path("GUIDE.md"))
        assert(newFile.lineStart == original.lineStart)
        assert(newStart.lineStart == 20)
        assert(newStart.lineEnd == original.lineEnd)
        assert(newEnd.lineEnd == 25)
        assert(newBody.body == "val y = 2")
        assert(newScope.visibility == Block.Visibility.Inherited)
        assert(newExp.expect == Block.Expectation.Runs)
        assert(newPlat.platform == Set(Block.Target.JVM))
        assert(newCarr.carrier == Block.Carrier.Hidden)
    }

    // Test 6: Block.Visibility.Env("foo") constructs and equals; singletons are distinct.
    "Block.Visibility.Env constructs and equals; Isolated, Inherited, Nested are singletons" in {
        val env1: Block.Visibility = Block.Visibility.Env("foo")
        val env2: Block.Visibility = Block.Visibility.Env("foo")
        val env3: Block.Visibility = Block.Visibility.Env("bar")
        assert(env1 == env2)
        assert(env1 != env3)
        assert(Block.Visibility.Isolated == Block.Visibility.Isolated)
        assert(Block.Visibility.Inherited == Block.Visibility.Inherited)
        assert(Block.Visibility.Nested == Block.Visibility.Nested)
        assert(Block.Visibility.Isolated != Block.Visibility.Inherited)
        assert(Block.Visibility.Inherited != Block.Visibility.Nested)
    }

    // Test 7: Public types live in kyo.doctest; Block is in kyo.doctest.internal.
    "Public types live in package kyo.doctest; Block lives under kyo.doctest.internal" in {
        assert(classOf[Doctest.type].getPackage.getName == "kyo.doctest")
        assert(classOf[Block].getPackage.getName == "kyo.doctest.internal")
        assert(classOf[Block.Visibility].getPackage.getName == "kyo.doctest.internal")
        assert(classOf[Block.Expectation].getPackage.getName == "kyo.doctest.internal")
        assert(classOf[Block.Target].getPackage.getName == "kyo.doctest.internal")
        assert(classOf[Block.Carrier].getPackage.getName == "kyo.doctest.internal")
        val internalPkg = classOf[Block].getPackage
        assert(internalPkg != null && internalPkg.getName == "kyo.doctest.internal")
    }

end PublicAPITest
