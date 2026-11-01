package kyo.lang

import dotty.tools.pc.ScalaPresentationCompiler
import java.net.URI
import kyo.*
import scala.jdk.CollectionConverters.*
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.pc.PresentationCompiler

/** The plugin inside the presentation compiler: an editor typing a `.kyo` buffer sees the direct view of an effectful expression. */
class KyoLangPluginTest extends kyo.test.Test[Any]:

    private val marked =
        """import kyo.*
          |object Example:
          |    def example =
          |        val text = Var.get[String]
          |        text.subs@@
          |""".stripMargin

    private def presentationCompiler(withPlugin: Boolean)(using Frame): PresentationCompiler < (Sync & Abort[Nothing] & Scope) =
        for
            plugin    <- Compilation.plugin
            classpath <- Compilation.classpath
            options = if withPlugin then List("-Xplugin:" + plugin.toString) else Nil
            pc <- Scope.acquireRelease(Sync.defer {
                // The presentation compiler's own API takes java.nio paths; the conversion sits at that boundary only.
                val entries = classpath.map(entry => java.nio.file.Path.of(entry.toString)).toList
                new ScalaPresentationCompiler().newInstance("kyo-lang", entries.asJava, options.asJava)
            })(pc => Sync.defer(pc.shutdown()))
        yield pc

    private def substringCompletions(pc: PresentationCompiler)(using Frame): Chunk[String] < Sync =
        Sync.defer {
            val params = CompilerOffsetParams(URI.create("file:///Example.kyo"), marked.replace("@@", ""), marked.indexOf("@@"))
            Chunk.from(pc.complete(params).get().getItems.asScala).map(_.getLabel).filter(_.startsWith("substring"))
        }

    "completions on a .kyo buffer" - {
        "without the plugin the binding is pending and String members are absent" in {
            for
                pc     <- presentationCompiler(withPlugin = false)
                labels <- substringCompletions(pc)
            yield assert(labels.isEmpty)
        }
        "with the plugin the binding is a String and its members complete" in {
            for
                pc     <- presentationCompiler(withPlugin = true)
                labels <- substringCompletions(pc)
            yield assert(labels.exists(_.startsWith("substring(")))
        }
    }
end KyoLangPluginTest
