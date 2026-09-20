package kyo.internal

import kyo.*
import scala.scalajs.js as sjs

/** The POSIX path syntax port against Node's own `path.posix`, and `Path` construction on a host without Node's modules.
  *
  * The comparison is exhaustive over every string of up to six tokens drawn from names, dot segments and separators, which covers each
  * branch of Node's `normalizeString`: a `..` removing a name, a `..` kept or dropped above the start, repeated and trailing separators,
  * and names made only of dots.
  */
class PathSyntaxJsTest extends kyo.test.Test[Any]:

    override def config = super.config.sequential

    private val tokens = Seq("a", "bc", ".", "..", "...", ".d", "/")

    private def inputs(maxTokens: Int): Iterator[String] = (1 to maxTokens).iterator.flatMap(n =>
        Iterator.fill(n)(tokens).foldLeft(Iterator(""))((acc, next) => acc.flatMap(p => next.map(p + _)))
    )

    private def nodePosix: sjs.Dynamic = PlatformJs.nodeBuiltin("node:path").get.posix

    /** Runs `f` with `process.getBuiltinModule` removed, the state of a host with no Node modules. A browser has no
      * `process` global at all, which is that state already, so there is nothing to remove and nothing to restore.
      */
    private def withoutGetBuiltinModule[A](f: => A): A =
        if sjs.typeOf(sjs.Dynamic.global.selectDynamic("process")) == "undefined" then f
        else
            val process = sjs.Dynamic.global.process
            val saved   = process.getBuiltinModule
            discard(sjs.special.delete(process, "getBuiltinModule"))
            try f
            finally process.updateDynamic("getBuiltinModule")(saved)
    end withoutGetBuiltinModule

    "posixNormalize matches Node's path.posix.normalize on every input".notBrowser in {
        val posix      = nodePosix
        val mismatches =
            inputs(6).filter(input => PathSyntaxJs.posixNormalize(input) != posix.normalize(input).asInstanceOf[String]).take(5).toList
        assert(mismatches == List.empty[String], s"inputs where the port and Node disagree: $mismatches")
    }

    "posixNormalize of the empty path is the current directory" in {
        assert(PathSyntaxJs.posixNormalize("") == ".")
    }

    "posixIsAbsolute matches Node's path.posix.isAbsolute on every input".notBrowser in {
        val posix      = nodePosix
        val mismatches = ("" +: inputs(4).toList).filter(input =>
            PathSyntaxJs.posixIsAbsolute(input) != posix.isAbsolute(input).asInstanceOf[Boolean]
        )
        assert(mismatches == List.empty[String], s"inputs where the port and Node disagree: $mismatches")
    }

    "a Path is built and read without Node's modules" in {
        assume(!Platform.isWindows, "Windows path syntax comes from the host's node:path")
        val (shown, parts, absolute) = withoutGetBuiltinModule {
            val path = Path("/", "a", "..", "b", ".", "c")
            (path.unsafe.show, path.parts, path.isAbsolute)
        }
        assert(shown == "/b/c")
        assert(parts == Chunk("", "b", "c"))
        assert(absolute)
    }

end PathSyntaxJsTest
