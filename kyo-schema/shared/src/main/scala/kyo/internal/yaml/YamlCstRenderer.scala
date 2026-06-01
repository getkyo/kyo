package kyo.internal.yaml

import kyo.*
import scala.annotation.tailrec

private[kyo] object YamlCstRenderer:

    def document(document: Yaml.Cst.Document)(using config: Yaml.WriterConfig): String =
        document.originalSource match
            case Present(source) => source
            case Absent =>
                if hasTrivia(document) then renderWithTrivia(document)
                else renderFromEvents(document)
        end match
    end document

    def stream(stream: Yaml.Cst.Stream)(using config: Yaml.WriterConfig): String =
        stream.originalSource match
            case Present(source) => source
            case Absent =>
                val builder = StringBuilder()
                val childConfig =
                    if stream.documents.size > 1 then config.copy(documentMarkers = Yaml.WriterConfig.DocumentMarkers.None)
                    else config

                @tailrec def loop(index: Int): String =
                    if index >= stream.documents.size then
                        appendStreamEndMarker()
                        builder.toString
                    else
                        if stream.documents.size > 1 then
                            if builder.nonEmpty && builder.charAt(builder.length - 1) != '\n' then
                                val _ = builder.append('\n')
                            val _ = builder.append("---\n")
                        end if
                        val _ = builder.append(document(stream.documents(index))(using childConfig))
                        loop(index + 1)
                    end if
                end loop

                def appendStreamEndMarker(): Unit =
                    if stream.documents.size > 1 && config.documentMarkers == Yaml.WriterConfig.DocumentMarkers.StartAndEnd then
                        if builder.nonEmpty && builder.charAt(builder.length - 1) != '\n' then
                            val _ = builder.append('\n')
                        val _ = builder.append("...\n")
                    end if
                end appendStreamEndMarker

                loop(0)
        end match
    end stream

    private def renderFromEvents(document: Yaml.Cst.Document)(using config: Yaml.WriterConfig): String =
        val renderer = Yaml.Events.Renderer(config)
        YamlCstBuilder.emitDocument(document, ())(renderer) match
            case Result.Success(_) => renderer.resultString
            case Result.Failure(e) => throw e
            case Result.Panic(e)   => throw e
        end match
    end renderFromEvents

    private def hasTrivia(document: Yaml.Cst.Document): Boolean =
        document.leadingTrivia.nonEmpty ||
            document.trailingTrivia.nonEmpty ||
            document.root.exists(hasTrivia)
    end hasTrivia

    private def hasTrivia(node: Yaml.Cst.Node): Boolean =
        node match
            case Yaml.Cst.Node.Mapping(entries, _, _, _, _) =>
                entries.exists(entry =>
                    entry.leadingTrivia.nonEmpty ||
                        entry.trailingTrivia.nonEmpty ||
                        hasTrivia(entry.key) ||
                        hasTrivia(entry.value)
                )
            case Yaml.Cst.Node.Sequence(entries, _, _, _, _) =>
                entries.exists(entry =>
                    entry.leadingTrivia.nonEmpty ||
                        entry.trailingTrivia.nonEmpty ||
                        hasTrivia(entry.value)
                )
            case _: Yaml.Cst.Node.Scalar | _: Yaml.Cst.Node.Alias =>
                false
        end match
    end hasTrivia

    private def renderWithTrivia(document: Yaml.Cst.Document)(using config: Yaml.WriterConfig): String =
        val renderer = TriviaRenderer(config)
        renderer.document(document)
    end renderWithTrivia

    final private class TriviaRenderer(config: Yaml.WriterConfig):
        private val out        = StringBuilder()
        private val indentSize = math.max(1, config.indent)

        def document(document: Yaml.Cst.Document): String =
            config.documentMarkers match
                case Yaml.WriterConfig.DocumentMarkers.Start | Yaml.WriterConfig.DocumentMarkers.StartAndEnd =>
                    out.append("---\n")
                case Yaml.WriterConfig.DocumentMarkers.None =>
                    ()
            end match

            appendTrivia(document.leadingTrivia, 0)
            document.root.foreach(renderNode(_, 0))
            appendTrivia(document.trailingTrivia, 0)
            if config.trailingNewline && out.nonEmpty && out.charAt(out.length - 1) != '\n' then
                out.append('\n')
            end if
            if config.documentMarkers == Yaml.WriterConfig.DocumentMarkers.StartAndEnd then
                out.append("...\n")
            end if
            out.toString
        end document

        private def renderNode(node: Yaml.Cst.Node, indent: Int): Unit =
            node match
                case Yaml.Cst.Node.Mapping(entries, _, _, _, _) =>
                    renderMapping(entries, indent)
                case Yaml.Cst.Node.Sequence(entries, _, _, _, _) =>
                    renderSequence(entries, indent)
                case Yaml.Cst.Node.Scalar(value, _, meta, _, _) =>
                    appendIndent(indent)
                    appendScalar(value, meta)
                    appendLine()
                case Yaml.Cst.Node.Alias(name, _, _, _) =>
                    appendIndent(indent)
                    val _ = out.append('*').append(name.value)
                    appendLine()
            end match
        end renderNode

        private def renderMapping(entries: Chunk[Yaml.Cst.MappingEntry], indent: Int): Unit =
            if entries.isEmpty then
                appendIndent(indent)
                out.append("{}")
                appendLine()
            else
                entries.foreach { entry =>
                    appendTrivia(entry.leadingTrivia, indent)
                    entry.value match
                        case Yaml.Cst.Node.Scalar(value, _, meta, _, _) =>
                            appendIndent(indent)
                            val _ = out.append(renderKey(entry.key)).append(": ")
                            appendScalar(value, meta)
                            appendTrailingTrivia(entry.trailingTrivia)
                            appendLine()
                        case Yaml.Cst.Node.Alias(name, _, _, _) =>
                            appendIndent(indent)
                            val _ = out.append(renderKey(entry.key)).append(": *").append(name.value)
                            appendTrailingTrivia(entry.trailingTrivia)
                            appendLine()
                        case child =>
                            appendIndent(indent)
                            val _ = out.append(renderKey(entry.key)).append(':')
                            appendTrailingTrivia(entry.trailingTrivia)
                            appendLine()
                            renderNode(child, indent + indentSize)
                    end match
                }
            end if
        end renderMapping

        private def renderSequence(entries: Chunk[Yaml.Cst.SequenceEntry], indent: Int): Unit =
            if entries.isEmpty then
                appendIndent(indent)
                out.append("[]")
                appendLine()
            else
                entries.foreach { entry =>
                    appendTrivia(entry.leadingTrivia, indent)
                    entry.value match
                        case Yaml.Cst.Node.Scalar(value, _, meta, _, _) =>
                            appendIndent(indent)
                            out.append("- ")
                            appendScalar(value, meta)
                            appendTrailingTrivia(entry.trailingTrivia)
                            appendLine()
                        case Yaml.Cst.Node.Alias(name, _, _, _) =>
                            appendIndent(indent)
                            val _ = out.append("- *").append(name.value)
                            appendTrailingTrivia(entry.trailingTrivia)
                            appendLine()
                        case child =>
                            appendIndent(indent)
                            out.append('-')
                            appendTrailingTrivia(entry.trailingTrivia)
                            appendLine()
                            renderNode(child, indent + indentSize)
                    end match
                }
            end if
        end renderSequence

        private def appendTrivia(trivia: Chunk[Yaml.Cst.Trivia], indent: Int): Unit =
            trivia.foreach { value =>
                appendIndent(indent)
                out.append(value.text.trim)
                appendLine()
            }
        end appendTrivia

        private def appendTrailingTrivia(trivia: Chunk[Yaml.Cst.Trivia]): Unit =
            trivia.foreach { value =>
                out.append(' ')
                out.append(value.text.trim)
            }
        end appendTrailingTrivia

        private def renderKey(node: Yaml.Cst.Node): String =
            node match
                case Yaml.Cst.Node.Scalar(value, _, _, _, _) =>
                    if plainKey(value) then value else doubleQuoted(value)
                case Yaml.Cst.Node.Alias(name, _, _, _) =>
                    "*" + name.value
                case _ =>
                    doubleQuoted(renderInline(node))
            end match
        end renderKey

        private def renderInline(node: Yaml.Cst.Node): String =
            node match
                case Yaml.Cst.Node.Scalar(value, _, _, _, _) => value
                case Yaml.Cst.Node.Alias(name, _, _, _)      => "*" + name.value
                case _                                       => node.toString
            end match
        end renderInline

        private def appendScalar(value: String, meta: Yaml.ScalarMeta): Unit =
            meta.style match
                case Yaml.ScalarStyle.SingleQuoted =>
                    val _ = out.append('\'').append(value.replace("'", "''")).append('\'')
                case Yaml.ScalarStyle.DoubleQuoted =>
                    out.append(doubleQuoted(value))
                case Yaml.ScalarStyle.Plain if plainScalar(value) =>
                    out.append(value)
                case Yaml.ScalarStyle.Plain =>
                    out.append(doubleQuoted(value))
                case Yaml.ScalarStyle.Literal | Yaml.ScalarStyle.Folded =>
                    out.append(doubleQuoted(value))
            end match
        end appendScalar

        private def plainKey(value: String): Boolean =
            value.nonEmpty && value.forall(ch => ch.isLetterOrDigit || ch == '_' || ch == '-')
        end plainKey

        private def plainScalar(value: String): Boolean =
            value.nonEmpty &&
                !value.exists(ch => ch == '\n' || ch == '\r') &&
                !value.headOption.exists(ch => ch.isWhitespace || ch == '#' || ch == '-' || ch == '?' || ch == ':') &&
                !value.endsWith(" ") &&
                !value.contains(": ") &&
                !value.contains(" #")
        end plainScalar

        private def doubleQuoted(value: String): String =
            val builder = StringBuilder()
            builder.append('"')
            value.foreach {
                case '"'  => builder.append("\\\"")
                case '\\' => builder.append("\\\\")
                case '\n' => builder.append("\\n")
                case '\r' => builder.append("\\r")
                case '\t' => builder.append("\\t")
                case ch   => builder.append(ch)
            }
            builder.append('"')
            builder.toString
        end doubleQuoted

        private def appendIndent(indent: Int): Unit =
            var index = 0
            while index < indent do
                out.append(' ')
                index += 1
            end while
        end appendIndent

        private def appendLine(): Unit =
            if config.trailingNewline || out.isEmpty || out.charAt(out.length - 1) != '\n' then
                out.append('\n')
            end if
        end appendLine
    end TriviaRenderer
end YamlCstRenderer
