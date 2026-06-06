package kyo

class YamlEventsTest extends kyo.test.Test[Any]:

    import YamlEventsTest.*

    given CanEqual[Any, Any] = CanEqual.derived

    "Yaml.Events" - {

        "visits parser events through the public event API" in {
            val yaml =
                """name: Alice
                  |items:
                  |  - one
                  |""".stripMargin

            val result =
                Yaml.Events.visit(yaml, Chunk.empty[String])(labelCollector)

            assert(
                result == Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::unknown",
                    "scalar:name:Plain",
                    "scalar:Alice:Plain",
                    "scalar:items:Plain",
                    "sequenceStart::unknown",
                    "scalar:one:Plain",
                    "collectionEnd:Sequence",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )
        }

        "supports method handlers and event object handlers through one protocol" in {
            val yaml = "name: Alice\n"

            val methodHandler = new Yaml.Events.Handler[Chunk[String], Nothing]:
                override def scalar(
                    context: Chunk[String],
                    value: String,
                    meta: Yaml.ScalarMeta
                ): Result[Nothing, Chunk[String]] =
                    Result.succeed(context :+ s"scalar:$value:${meta.style}")
            end methodHandler

            val eventHandler = new Yaml.Events.EventHandler[Chunk[String], Nothing]:
                override def event(context: Chunk[String], event: Yaml.Events.Event): Result[Nothing, Chunk[String]] =
                    Result.succeed(context :+ label(event))
            end eventHandler

            assert(Yaml.Events.visit(yaml, Chunk.empty[String])(methodHandler) == Result.succeed(Chunk(
                "scalar:name:Plain",
                "scalar:Alice:Plain"
            )))
            assert(Yaml.Events.visit(yaml, Chunk.empty[String])(eventHandler) == Result.succeed(Chunk(
                "streamStart",
                "documentStart",
                "mappingStart::unknown",
                "scalar:name:Plain",
                "scalar:Alice:Plain",
                "collectionEnd:Mapping",
                "documentEnd",
                "streamEnd"
            )))
        }

        "renders transformed parser events through the public renderer" in {
            val renderer = Yaml.Events.Renderer(Yaml.WriterConfig.Default)
            val uppercase =
                Yaml.Events.Processor.mapScalars[DecodeException]((value, meta) => Result.succeed((value.toUpperCase, meta)))
            val yaml =
                """name: Alice
                  |city: Paris
                  |""".stripMargin

            val result =
                Yaml.Events.visit(yaml, ())(uppercase.andThen(renderer)).map(_ => renderer.resultString)

            assert(
                result == Result.succeed(
                    """NAME: ALICE
                      |CITY: PARIS
                      |""".stripMargin
                )
            )
        }

        "writes schema values into the public event API" in {
            val result =
                Yaml.Events.write(MTPerson("Alice", 30), Chunk.empty[String])(labelCollector)

            assert(
                result == Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::2",
                    "scalar:name:Plain",
                    "scalar:Alice:Plain",
                    "scalar:age:Plain",
                    "scalar:30:Plain",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )
        }

        "writes schema events through processors into the public renderer" in {
            val renderer = Yaml.Events.Renderer(Yaml.WriterConfig.Default)
            val uppercase =
                Yaml.Events.Processor.mapScalars[DecodeException]((value, meta) => Result.succeed((value.toUpperCase, meta)))

            val result =
                Yaml.Events.write(MTPerson("Alice", 30), ())(uppercase.andThen(renderer)).map(_ => renderer.resultString)

            assert(
                result == Result.succeed(
                    """NAME: ALICE
                      |AGE: 30
                      |""".stripMargin
                )
            )
        }

        "visits a selected document from a stream through the public event API" in {
            val yaml =
                """---
                  |name: Alice
                  |---
                  |name: Bob
                  |""".stripMargin

            val result =
                Yaml.Events.visit(yaml, Yaml.DocumentIndex(1), Chunk.empty[String])(labelCollector)

            assert(
                result == Result.succeed(Chunk(
                    "streamStart",
                    "documentStart",
                    "mappingStart::unknown",
                    "scalar:name:Plain",
                    "scalar:Bob:Plain",
                    "collectionEnd:Mapping",
                    "documentEnd",
                    "streamEnd"
                ))
            )
        }

        "uses the contextual writer config for the default public renderer" in {
            given Yaml.WriterConfig = Yaml.WriterConfig.Small

            val renderer = Yaml.Events.Renderer()

            val result =
                Yaml.Events.write(List(1, 2), ())(renderer).map(_ => renderer.resultString)

            assert(result == Result.succeed("[1, 2]"))
        }

        "audits anchors and aliases with typed YAML metadata" in {
            val tag      = Yaml.YamlTag("!custom")
            val defaults = Yaml.Anchor("defaults")

            assert(tag.value == "!custom")
            assert(defaults.value == "defaults")
            assert((tag match
                case Yaml.YamlTag(value) => value
            ) == "!custom")
            assert((defaults match
                case Yaml.Anchor(value) => value
            ) == "defaults")

            val yaml =
                """defaults: &defaults
                  |  retries: 3
                  |database: &database
                  |  host: localhost
                  |workers: &workers
                  |  - api
                  |service:
                  |  config: *defaults
                  |  connection: *database
                  |  pool: *workers
                  |orphan: *missing
                  |unused: &unused value
                  |""".stripMargin

            val expected = AnchorAudit(
                declared = Set(Yaml.Anchor("defaults"), Yaml.Anchor("database"), Yaml.Anchor("workers"), Yaml.Anchor("unused")),
                used = Set(Yaml.Anchor("defaults"), Yaml.Anchor("database"), Yaml.Anchor("workers"), Yaml.Anchor("missing")),
                declarationSources = Set(
                    Yaml.Anchor("defaults") -> "mapping",
                    Yaml.Anchor("database") -> "mapping",
                    Yaml.Anchor("workers")  -> "sequence",
                    Yaml.Anchor("unused")   -> "scalar"
                )
            )

            val result = Yaml.Events.visit(yaml, AnchorAudit.empty)(AnchorAuditHandler)

            assert(result == Result.succeed(expected))
            assert(result.map(_.undeclared) == Result.succeed(Set(Yaml.Anchor("missing"))))
            assert(result.map(_.unused) == Result.succeed(Set(Yaml.Anchor("unused"))))
        }

        "can build a YAML node tree with an event handler and typed errors" in {
            val yaml =
                """profile:
                  |  name: !user &name Alice
                  |  tags: [scala, yaml]
                  |again: *name
                  |""".stripMargin

            val visited: Result[NodeTreeError | DecodeException, Yaml.Node] =
                Yaml.Events.visit(yaml, NodeTreeContext.empty)(NodeTreeHandler).flatMap(_.finish)

            val parsed = Yaml.parse(yaml).getOrThrow

            assert(visited == Result.succeed(parsed))
            parsed match
                case Yaml.Node.Mapping(entries, _) =>
                    assert(entries.size == 2)
                    entries(0) match
                        case (Yaml.Node.Scalar("profile", _), Yaml.Node.Mapping(profileEntries, _)) =>
                            assert(profileEntries.size == 2)
                        case other => fail(s"Expected profile mapping, got $other")
                    end match
                    entries(1) match
                        case (Yaml.Node.Scalar("again", _), Yaml.Node.Alias(Yaml.Anchor("name"), _)) => ()
                        case other => fail(s"Expected alias entry, got $other")
                    end match
                case other => fail(s"Expected mapping, got $other")
            end match
        }
    }
end YamlEventsTest

private object YamlEventsTest:

    val labelCollector = new Yaml.Events.EventHandler[Chunk[String], Nothing]:
        override def event(context: Chunk[String], event: Yaml.Events.Event): Result[Nothing, Chunk[String]] =
            Result.succeed(context :+ label(event))
    end labelCollector

    def label(event: Yaml.Events.Event): String =
        event match
            case Yaml.Events.Event.StreamStart(_)            => "streamStart"
            case Yaml.Events.Event.DocumentStart(_)          => "documentStart"
            case Yaml.Events.Event.MappingStart(meta, size)  => s"mappingStart:${anchor(meta.anchor)}:${sizeLabel(size)}"
            case Yaml.Events.Event.SequenceStart(meta, size) => s"sequenceStart:${anchor(meta.anchor)}:${sizeLabel(size)}"
            case Yaml.Events.Event.Scalar(value, meta)       => s"scalar:$value:${meta.style}"
            case Yaml.Events.Event.Alias(name, _)            => s"alias:${name.value}"
            case Yaml.Events.Event.CollectionEnd(kind, _)    => s"collectionEnd:$kind"
            case Yaml.Events.Event.DocumentEnd(_)            => "documentEnd"
            case Yaml.Events.Event.StreamEnd(_)              => "streamEnd"
    end label

    def anchor(anchor: Maybe[Yaml.Anchor]): String =
        anchor.map(_.value).getOrElse("")

    def sizeLabel(size: Maybe[Int]): String =
        size.map(_.toString).getOrElse("unknown")

    final case class AnchorAudit(
        declared: Set[Yaml.Anchor],
        used: Set[Yaml.Anchor],
        declarationSources: Set[(Yaml.Anchor, String)]
    ) derives CanEqual:
        def undeclared: Set[Yaml.Anchor] = used -- declared
        def unused: Set[Yaml.Anchor]     = declared -- used
    end AnchorAudit

    object AnchorAudit:
        val empty: AnchorAudit = AnchorAudit(Set.empty, Set.empty, Set.empty)
    end AnchorAudit

    object AnchorAuditHandler extends Yaml.Events.EventHandler[AnchorAudit, Nothing]:
        override def event(context: AnchorAudit, event: Yaml.Events.Event): Result[Nothing, AnchorAudit] =
            event match
                case Yaml.Events.Event.MappingStart(meta, _)  => Result.succeed(declare(context, meta.anchor, "mapping"))
                case Yaml.Events.Event.SequenceStart(meta, _) => Result.succeed(declare(context, meta.anchor, "sequence"))
                case Yaml.Events.Event.Scalar(_, meta)        => Result.succeed(declare(context, meta.anchor, "scalar"))
                case Yaml.Events.Event.Alias(name, _)         => Result.succeed(context.copy(used = context.used + name))
                case _                                        => Result.succeed(context)

        private def declare(context: AnchorAudit, anchor: Maybe[Yaml.Anchor], source: String): AnchorAudit =
            anchor.fold(context)(a =>
                context.copy(
                    declared = context.declared + a,
                    declarationSources = context.declarationSources + (a -> source)
                )
            )
        end declare
    end AnchorAuditHandler

    enum NodeTreeError derives CanEqual:
        case MappingKeyWithoutValue(key: Yaml.Node)
        case MultipleRootNodes(first: Yaml.Node, second: Yaml.Node)
        case UnexpectedCollectionEnd(mark: Yaml.Mark)
        case UnexpectedCollectionKind(expected: Yaml.Events.CollectionKind, actual: Yaml.Events.CollectionKind)
        case UnclosedNode(mark: Yaml.Mark)
        case MissingNode(mark: Yaml.Mark)
    end NodeTreeError

    final case class NodeTreeContext(root: Maybe[Yaml.Node], stack: List[BuildFrame], lastMark: Yaml.Mark) derives CanEqual:
        def finish: Result[NodeTreeError, Yaml.Node] =
            root match
                case Present(node) if stack.isEmpty => Result.succeed(node)
                case Present(_)                     => Result.fail(NodeTreeError.UnclosedNode(lastMark))
                case Absent                         => Result.fail(NodeTreeError.MissingNode(lastMark))
            end match
        end finish
    end NodeTreeContext

    object NodeTreeContext:
        val empty: NodeTreeContext = NodeTreeContext(Absent, Nil, Yaml.Mark(0, 1, 1))
    end NodeTreeContext

    sealed trait BuildFrame derives CanEqual

    final case class MappingBuildFrame(
        meta: Yaml.Meta,
        entries: Chunk[(Yaml.Node, Yaml.Node)],
        pendingKey: Maybe[Yaml.Node]
    ) extends BuildFrame

    final case class SequenceBuildFrame(meta: Yaml.Meta, elements: Chunk[Yaml.Node]) extends BuildFrame

    object NodeTreeHandler extends Yaml.Events.EventHandler[NodeTreeContext, NodeTreeError]:
        override def event(context: NodeTreeContext, event: Yaml.Events.Event): Result[NodeTreeError, NodeTreeContext] =
            val next = context.copy(lastMark = mark(event))
            event match
                case Yaml.Events.Event.MappingStart(meta, _) =>
                    Result.succeed(next.copy(stack = MappingBuildFrame(meta, Chunk.empty, Absent) :: next.stack))
                case Yaml.Events.Event.SequenceStart(meta, _) =>
                    Result.succeed(next.copy(stack = SequenceBuildFrame(meta, Chunk.empty) :: next.stack))
                case Yaml.Events.Event.Scalar(value, meta) =>
                    addNode(next, Yaml.Node.Scalar(value, meta))
                case Yaml.Events.Event.Alias(name, aliasMark) =>
                    addNode(next, Yaml.Node.Alias(name, aliasMark))
                case Yaml.Events.Event.CollectionEnd(kind, endMark) =>
                    closeCollection(next, kind, endMark)
                case _ =>
                    Result.succeed(next)
            end match
        end event

        private def closeCollection(
            context: NodeTreeContext,
            kind: Yaml.Events.CollectionKind,
            mark: Yaml.Mark
        ): Result[NodeTreeError, NodeTreeContext] =
            import Yaml.Events.CollectionKind.*

            context.stack match
                case MappingBuildFrame(meta, entries, pendingKey) :: rest =>
                    if kind != Mapping then Result.fail(NodeTreeError.UnexpectedCollectionKind(Mapping, kind))
                    else
                        pendingKey match
                            case Present(key) => Result.fail(NodeTreeError.MappingKeyWithoutValue(key))
                            case Absent       => addNode(context.copy(stack = rest), Yaml.Node.Mapping(entries, meta))
                        end match
                case SequenceBuildFrame(meta, elements) :: rest =>
                    if kind != Sequence then Result.fail(NodeTreeError.UnexpectedCollectionKind(Sequence, kind))
                    else addNode(context.copy(stack = rest), Yaml.Node.Sequence(elements, meta))
                case Nil =>
                    Result.fail(NodeTreeError.UnexpectedCollectionEnd(mark))
            end match
        end closeCollection

        private def addNode(context: NodeTreeContext, node: Yaml.Node): Result[NodeTreeError, NodeTreeContext] =
            context.stack match
                case MappingBuildFrame(meta, entries, pendingKey) :: rest =>
                    pendingKey match
                        case Present(key) =>
                            Result.succeed(context.copy(stack = MappingBuildFrame(meta, entries :+ (key -> node), Absent) :: rest))
                        case Absent =>
                            Result.succeed(context.copy(stack = MappingBuildFrame(meta, entries, Maybe(node)) :: rest))
                    end match
                case SequenceBuildFrame(meta, elements) :: rest =>
                    Result.succeed(context.copy(stack = SequenceBuildFrame(meta, elements :+ node) :: rest))
                case Nil =>
                    context.root match
                        case Present(first) => Result.fail(NodeTreeError.MultipleRootNodes(first, node))
                        case Absent         => Result.succeed(context.copy(root = Maybe(node)))
                    end match
            end match
        end addNode

        private def mark(event: Yaml.Events.Event): Yaml.Mark =
            event match
                case Yaml.Events.Event.StreamStart(mark)      => mark
                case Yaml.Events.Event.DocumentStart(mark)    => mark
                case Yaml.Events.Event.MappingStart(meta, _)  => meta.mark
                case Yaml.Events.Event.SequenceStart(meta, _) => meta.mark
                case Yaml.Events.Event.Scalar(_, meta)        => meta.mark
                case Yaml.Events.Event.Alias(_, mark)         => mark
                case Yaml.Events.Event.CollectionEnd(_, mark) => mark
                case Yaml.Events.Event.DocumentEnd(mark)      => mark
                case Yaml.Events.Event.StreamEnd(mark)        => mark
        end mark
    end NodeTreeHandler
end YamlEventsTest
