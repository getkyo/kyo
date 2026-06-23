package demo

import kyo.*

/** RepoExplorer: a semantic Scala-code MCP server backed by kyo-tasty.
  *
  * Instead of grepping text, this server reads the TASTy on its own runtime classpath (the kyo
  * library and its dependencies) and answers questions about the actual typed declarations:
  *
  *   - `find-type` (typed tool): a fully-qualified name to its kind, members, and member signatures.
  *   - `find-by-name` (typed tool): a simple name to the fully-qualified names that match.
  *   - `outline` (raw tool): a Markdown rendering of a type's members, via `ToolOutcome.ok`.
  *   - `tasty://{fqn}` (templated resource) renders the same outline; `tasty://index` (static)
  *     reports how many classes are loaded.
  *   - completion suggests fully-qualified names as you type the `fqn` resource argument.
  *
  * The classpath is read once at startup (`Tasty.withClasspath`); every handler then queries the
  * captured immutable `Classpath`. A missing symbol is the typed `SymbolNotFound`, mapped to a wire
  * error via `.error`, not a panic.
  *
  * Run as a stdio MCP server: `java -cp <kyo-mcpJVM test classpath> demo.RepoExplorer`.
  */
object RepoExplorer extends KyoApp:

    case class FqnIn(fqn: String) derives Schema, CanEqual
    case class NameIn(name: String) derives Schema, CanEqual
    case class MemberInfo(name: String, kind: String, signature: String) derives Schema, CanEqual
    case class TypeInfo(fqn: String, kind: String, members: Chunk[MemberInfo]) derives Schema, CanEqual
    case class MatchList(matches: Chunk[String]) derives Schema, CanEqual
    case class SymbolNotFound(fqn: String) derives Schema, CanEqual

    private val templateUri = McpResourceUri("tasty://{fqn}")

    private def kindOf(s: Tasty.Symbol): String =
        s match
            case _: Tasty.Symbol.Object    => "object"
            case _: Tasty.Symbol.Trait     => "trait"
            case _: Tasty.Symbol.Class     => "class"
            case _: Tasty.Symbol.Method    => "def"
            case _: Tasty.Symbol.Val       => "val"
            case _: Tasty.Symbol.Field     => "field"
            case _: Tasty.Symbol.TypeAlias => "type"
            case _                         => "symbol"

    private def serve(cp: Tasty.Classpath, classNames: Chunk[String])(using Frame): Unit < (Async & Scope) =
        def sigOf(s: Tasty.Symbol): String =
            s match
                case m: Tasty.Symbol.Method => cp.signature(m)
                case _                      => ""

        def membersOf(s: Tasty.Symbol): Chunk[MemberInfo] =
            s match
                case cl: Tasty.Symbol.ClassLike =>
                    cp.members(cl, Tasty.MemberScope.Declared)
                        .filterNot(m => m.isSynthetic || m.isArtifact || m.isParamAccessor || m.simpleName == "<init>")
                        .map(m => MemberInfo(m.simpleName, kindOf(m), sigOf(m)))
                case _ => Chunk.empty

        def renderOutline(fqn: String): String =
            cp.findSymbol(fqn) match
                case Present(s) =>
                    val body = membersOf(s)
                        .map(m => if m.signature.nonEmpty then s"- ${m.signature}" else s"- ${m.kind} ${m.name}")
                        .mkString("\n")
                    if body.isEmpty then s"# $fqn (${kindOf(s)})" else s"# $fqn (${kindOf(s)})\n\n$body"
                case Absent => s"(not found: $fqn)"

        val findType =
            McpHandler.tool[FqnIn]("find-type", "Look up a fully-qualified type and its declared members") { in =>
                cp.findSymbol(in.fqn) match
                    case Present(s) => TypeInfo(in.fqn, kindOf(s), membersOf(s))
                    case Absent     => Abort.fail(SymbolNotFound(in.fqn))
            }.error[SymbolNotFound](-40020, "symbol not found")

        val findByName =
            McpHandler.tool[NameIn]("find-by-name", "Find fully-qualified names whose simple name matches") { in =>
                MatchList(classNames.filter(_.split('.').lastOption.contains(in.name)))
            }

        val outline =
            McpHandler.toolRaw[FqnIn]("outline", "Render a type's outline as Markdown") { in =>
                McpHandler.ToolOutcome.ok(McpContent.text(renderOutline(in.fqn)))
            }

        val typeResource =
            McpHandler.resourceTemplate(McpResourceUri.Template("tasty://{fqn}"), "TASTy type view", "Structured outline of a type") { m =>
                Chunk(McpHandler.ResourceBody.text(renderOutline(m.bindings.getOrElse("fqn", ""))))
            }

        val indexResource =
            McpHandler.resource(McpResourceUri("tasty://index"), "Classpath index", "How many types are loaded") {
                Chunk(McpHandler.ResourceBody.text(s"${classNames.size} types loaded from the runtime classpath"))
            }

        val completeFqn =
            McpHandler.completion(McpHandler.CompletionRef.Resource(templateUri)) { arg =>
                McpHandler.CompletionOutcome.of(classNames.filter(_.startsWith(arg.value)).take(50)*)
            }

        val handlers = Seq[McpHandler[?, ?, ?]](findType, findByName, outline, typeResource, indexResource, completeFqn)
        JsonRpcTransport.stdio().map(t => McpServer.initWith(t, handlers*)(_ => Async.never))
    end serve

    run {
        for
            classpathProp <- System.property[String]("java.class.path")
            sepProp       <- System.property[String]("path.separator")
            roots = classpathProp.getOrElse("").split(sepProp.getOrElse(":")).toIndexedSeq.filter(_.nonEmpty)
            loaded <- Tasty.withClasspath(roots) {
                for
                    cp      <- Tasty.classpath
                    classes <- Tasty.allClassLike
                yield (cp, classes.map(c => cp.fullName(c).toString))
            }
            served <- serve(loaded._1, loaded._2)
        yield served
    }
end RepoExplorer
