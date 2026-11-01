package kyo.lang

import dotty.tools.pc.ScalaPresentationCompiler
import java.io.File
import java.net.URI
import java.nio.file.Path
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

    private def presentationCompiler(withPlugin: Boolean)(using Frame): PresentationCompiler < (Sync & Scope) =
        Scope.acquireRelease(Sync.defer {
            val classpath = java.lang.System.getProperty("java.class.path").split(File.pathSeparator).toList.map(Path.of(_))
            val options   = if withPlugin then List("-Xplugin:" + Compilation.plugin.toString) else Nil
            new ScalaPresentationCompiler().newInstance("kyo-lang", classpath.asJava, options.asJava)
        })(pc => Sync.defer(pc.shutdown()))

    private def substringCompletions(pc: PresentationCompiler)(using Frame): List[String] < Sync =
        Sync.defer {
            val params = CompilerOffsetParams(URI.create("file:///Example.kyo"), marked.replace("@@", ""), marked.indexOf("@@"))
            pc.complete(params).get().getItems.asScala.map(_.getLabel).filter(_.startsWith("substring")).toList
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
