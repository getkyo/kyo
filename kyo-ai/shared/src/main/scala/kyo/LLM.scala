package kyo

import java.lang.ref.WeakReference
import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.Image
import kyo.ai.completion.Completion
import kyo.kernel.*

/** A typed effect representing first-class conversations with a large language model.
  *
  * `LLM` is a custom `ArrowEffect` whose ops carry data and read/append per-instance conversation histories
  * held in one threaded `State`; a program typed `< LLM` has no `Async` in its row. The one op that reaches
  * the world is `Gen`, whose handler runs the eval loop: that is where `Async` and `Abort[AIGenException]`
  * enter, riding out on `run`'s residual. `AI` identifies one conversation slot.
  *
  * @see
  *   [[kyo.ai.Context]] for the conversation history
  * @see
  *   [[kyo.AIException]] for the module's failure hierarchy
  * @see
  *   [[kyo.Tool]], [[kyo.Thought]], [[kyo.Prompt]], [[kyo.Mode]] for the composable generation surface
  */
sealed trait LLM extends ArrowEffect[LLM.internal.Op, Id]

object LLM:

    import internal.*
    export kyo.ai.Config

    private given Tag[LLM] = Tag.derive[LLM]

    /** The one threaded record: per-instance histories/enablements keyed by a `WeakReference` to the `AI`
      * (so dropped instances are reclaimable), the monotonic id counter `Init` draws from, an `owner` token
      * stamped on every instance (so a cross-run use is detectable), and the scope env.
      */
    final case class State private[kyo] (
        instances: Dict[internal.AIRef, AISession],
        nextId: Long,
        owner: AnyRef,
        env: AIEnv
    ):
        private[kyo] def sessionOf(ai: AI): AISession             = instances.get(ai.ref).getOrElse(AISession.empty)
        private[kyo] def contextOf(ai: AI): Context               = sessionOf(ai).rawContext
        private[kyo] def withSession(ai: AI, s: AISession): State = copy(instances = instances.update(ai.ref, s))
        private[kyo] def withContext(ai: AI, ctx: Context): State = withSession(ai, sessionOf(ai).copy(rawContext = ctx))
        private[kyo] def without(ai: AI): State                   = copy(instances = instances.remove(ai.ref))
        // Drops slots whose AI has been GC'd; run at each mint so an unbounded mint stream never
        // accumulates dead slots.
        private[kyo] def pruned: State = copy(instances = instances.filter((ref, _) => ref.isValid))
    end State

    private[kyo] object State:
        def empty(config: Config): State =
            // A fresh owner per run (object identity, no counter); every instance minted in this run carries it.
            State(Dict.empty, 0L, new AnyRef, AIEnv(Present(config), Prompt.empty, Chunk.empty, Chunk.empty, Chunk.empty))
    end State

    // An op targeting an instance from a DIFFERENT run (owner differs) can't address this run's slots, so
    // fail loud instead of silently reading/writing a same-id but wrong entry.
    private def crossRunFailure(op: Op[?], state: State)(using Frame): Maybe[AICrossRunException] =
        val target: Maybe[AI] = op match
            case o: Op.Read       => Present(o.target)
            case o: Op.Add        => Present(o.target)
            case o: Op.Set        => Present(o.target)
            case o: Op.Gen[?]     => Present(o.target)
            case o: Op.Stream[?]  => Present(o.target)
            case o: Op.Discard    => Present(o.target)
            case o: Op.GetSession => Present(o.target)
            case o: Op.SetSession => Present(o.target)
            case _                => Absent
        target match
            case Present(ai) if ai.owner ne state.owner => Present(AICrossRunException(ai.id))
            case _                                      => Absent
        end match
    end crossRunFailure

    /** Threads `State` through `ArrowEffect.handleLoop`, interpreting each op. NOT inline. */
    private[kyo] def runWith[A, S, B, S2](state: State)(v: A < (LLM & S))(
        done: (State, A) => B < S2
    )(using Frame): B < (S & S2 & Async & Abort[AIGenException]) =
        ArrowEffect.handleLoop[Op, Id, LLM, A, B, S, S2 & Async & Abort[AIGenException], State](Tag[LLM], state, v)(
            handle = [C] =>
                (input: Op[C], state: State, cont) =>
                    input match
                        // The panic is the arm's result, so it rides runWith's residual Abort row (not the
                        // LLM continuation), aborting the whole computation.
                        case _ if crossRunFailure(input, state).nonEmpty =>
                            Abort.panic(crossRunFailure(input, state).get)
                        case op: Op.Read =>
                            Loop.continue(state, cont(state.contextOf(op.target)))
                        case op: Op.Add =>
                            Loop.continue(state.withContext(op.target, state.contextOf(op.target).add(op.message)), cont(()))
                        case op: Op.Set =>
                            Loop.continue(state.withContext(op.target, op.context), cont(()))
                        case _: Op.Init.type =>
                            // Mint with the next id from the threaded State (no global counter), stamped with
                            // this run's owner; prune GC'd slots on the way.
                            val ai = new AI(state.nextId, state.owner)
                            Loop.continue(state.pruned.copy(nextId = state.nextId + 1).withContext(ai, Context.empty), cont(ai))
                        case _: Op.Env.type =>
                            Loop.continue(state, cont(state.env))
                        case op: Op.SetEnv =>
                            Loop.continue(state.copy(env = op.env), cont(state.env))
                        case op: Op.Discard =>
                            // Eager interrupt on instance discard: stop this instance's in-flight preparation run
                            // before dropping its slot; the run-level Sync.ensure is the backstop on other exits.
                            val eager = state.sessionOf(op.target).preparation match
                                case Present(p) => p.inFlight.get.map { case Present(f) => f.interrupt; case Absent => false }
                                case Absent     => Kyo.lift(false)
                            eager.andThen(Loop.continue(state.without(op.target), cont(())))
                        case _: Op.GetState.type =>
                            Loop.continue(state, cont(state))
                        case op: Op.SetState =>
                            // Restoring a snapshot must never lower the id counter: ids stay monotonic so a
                            // slot key is never reused (a rollback keeps the high-water id).
                            Loop.continue(op.state.copy(nextId = math.max(state.nextId, op.state.nextId)), cont(()))
                        case op: Op.GetSession =>
                            Loop.continue(state, cont(state.sessionOf(op.target)))
                        case op: Op.SetSession =>
                            Loop.continue(state.withSession(op.target, op.session), cont(()))
                        case op: Op.Gen[C] @unchecked =>
                            // The eval loop is itself an LLM computation (reads config, appends replies, runs
                            // tools), so a nested runWith against the live state discharges those ops and
                            // threads the updated state back; Async & Abort enter here and ride out on run's
                            // residual.
                            runWith(state)(genLoop(op.target, op.schema))((s, c) => (s, c))
                                .map((s, c) => Loop.continue(s, cont(c)))
                        case op: Op.Stream[C] @unchecked =>
                            // The SSE projection is itself an LLM computation (reads config, assembles the
                            // result-tool/context), so a nested runWith discharges those LLM ops; on full
                            // consumption it records the turn through the same state, and at an update boundary
                            // the projection writes the rendered compacted list back through the compaction seam,
                            // so the threaded state carries that update out. The op carries the element-emit Tag
                            // captured at the suspend site, since the handler's C is abstract and the Tag cannot
                            // be re-derived here.
                            runWith(state)(streamAgainst(op.target, op.schema)(using summon[Frame], op.emitTag))((s, c) => (s, c))
                                .map((s, c) => Loop.continue(s, cont(c)))
            ,
            done = (state, a) => done(state, a)
        )
    end runWith

    /** Closes the open string (if any) and balances open brackets in a partial JSON buffer, so a streaming
      * prefix decodes into the value parsed so far. A prefix that ends mid-token (mid-number, mid-keyword)
      * fails the subsequent decode and is retried as more data arrives.
      */
    private[kyo] def completePartialJson(buf: String): String =
        @scala.annotation.tailrec
        def loop(i: Int, inString: Boolean, escaped: Boolean, closers: List[Char]): String =
            if i >= buf.length then
                val closed = if inString && !escaped then buf + "\"" else buf
                closers.foldLeft(closed)(_ + _)
            else
                val c = buf.charAt(i)
                if inString then
                    if escaped then loop(i + 1, true, false, closers)
                    else if c == '\\' then loop(i + 1, true, true, closers)
                    else if c == '"' then loop(i + 1, false, false, closers)
                    else loop(i + 1, true, false, closers)
                else
                    c match
                        case '"'       => loop(i + 1, true, false, closers)
                        case '{'       => loop(i + 1, false, false, '}' :: closers)
                        case '['       => loop(i + 1, false, false, ']' :: closers)
                        case '}' | ']' => loop(i + 1, false, false, if closers.isEmpty then closers else closers.tail)
                        case _         => loop(i + 1, inString, escaped, closers)
                end if
        loop(0, false, false, Nil)
    end completePartialJson

    /** Projects the conversation as a streaming completion: posts the SSE request, accumulates the
      * result_tool's argument fragments, and emits decoded values. `String` emits text chunks that
      * concatenate; every other type emits complete array elements. The returned `Stream` carries its I/O in
      * the element row (the SSE connection scoped to close on termination or error) and its failures typed as
      * `Abort[AIStreamException]`. A missing API key is the one failure raised eagerly, before the `Stream`
      * value, so it rides the run boundary as an `AIMissingApiKeyException` (an `AIGenException`).
      */
    private[kyo] def streamAgainst[A](target: AI, schema: Schema[A])(using
        Frame,
        Tag[Emit[Chunk[A]]]
    ): Stream[A, LLM & Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        given Schema[A] = schema
        // Resolve config, the compactor, and tool metadata the SAME way genLoop resolves them for gen
        // (instance-over-scope), so `ai.stream` compacts identically to `ai.gen` for a named instance: the
        // merged env means render's occupancy/config read see the instance's overrides. Prompt/thoughts/modes
        // stay scope-only (the stream path sends only the result tool and never loops them).
        LLM.session(target).map { session =>
            LLM.updateEnv(scopeEnv =>
                scopeEnv
                    .copy(config = session.env.config.orElse(scopeEnv.config))
                    .copy(compactor = session.env.compactor.orElse(scopeEnv.compactor))
                    .addTools(session.env.tools)
            ) {
                AI.config.map { config =>
                    if config.provider.usesApiKey && config.apiKey.isEmpty then
                        Abort.fail[AIGenException](AIMissingApiKeyException(config.modelName))
                    else
                        // The result_tool rides every request: its definition plus prompt go into the request
                        // body via enrichedContext.
                        val toolInfos = Tool.internal.resultToolDefinition.infos
                        // Mode inferred from A: String streams incrementally (each element the next decoded text
                        // chunk), every other type streams complete array elements. A bare partial scalar has no
                        // meaning, so only String takes the incremental path; everything else takes complete-element.
                        val stringMode = schema.structure match
                            case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => true
                            case _                                                           => false
                        Abort.recover[HttpException](e => Abort.fail(Completion.classifyHttp(config, e)))(
                            Compactor.internal.applyStreamMeasure(target, config)
                        ).andThen(context(target)).map { targetContext =>
                            // Apply a pending stream re-anchor at the turn start, then render. Compaction seam for
                            // the stream path, shared with gen via renderView: below the occupancy trigger re-serve
                            // the context unchanged, at/above it render + install the rebuilt compacted list. The
                            // compactor is read from the merged env (instance-over-scope).
                            LLM.env.map(e => renderView(target, targetContext, config, e.compactor)).map { view =>
                                // Streaming has no eval loop and no repair turn: the result must arrive as a
                                // result_tool call on THIS turn. HTTP backends compel it by protocol (forced
                                // tool_choice); command harnesses have no forcing knob, so the request carries an
                                // explicit result directive, request-scoped and riding every backend identically.
                                // It builds on the rendered view; the anchor basis stays the pre-directive
                                // view.compacted, so the request-scoped directive joins the excluded enrichment.
                                val requestView = view.systemMessage(
                                    s"Deliver the result by calling the '${Completion.resultToolName}' tool: only " +
                                        "the tool call's arguments reach the caller, so do not reply in plain text."
                                )
                                Prompt.internal.enrichedContext(requestView, toolInfos).map { context =>
                                    // Wrap in the {resultValue: A} object envelope (array of A in element mode):
                                    // providers require an object tool schema, so a bare non-object schema is
                                    // rejected. The array schema comes from kyo-schema's chunk Schema, not hand-built.
                                    val resultValueSchema =
                                        if stringMode then Json.jsonSchema[A]
                                        else Json.jsonSchema(using Schema.chunkSchema(using schema))
                                    val resultSchema             = Thought.internal.resultJson(Chunk.empty, resultValueSchema)
                                    val completion               = config.provider.completion
                                    val (tokenizer, tokenizerId) = Compactor.internal.activeTokenizer(config)
                                    // Seat the stream re-anchor: the usage sink is written by the adapter SSE
                                    // projection at stream end (outside the LLM handler), so it is an AtomicRef; the
                                    // sent view + active tokenizer are captured here (LLM live) for applyStreamMeasure
                                    // to consume at the next turn's start. The anchor basis is the pre-enrichment,
                                    // pre-directive view.compacted, the same non-enriched list occupancy apportions
                                    // over on subsequent turns.
                                    AtomicRef.init(Maybe.empty[Completion.Usage]).map { usageSink =>
                                        LLM.session(target).map { session =>
                                            val anchor =
                                                Compactor.internal.StreamAnchor(usageSink, view.compacted, tokenizer, tokenizerId)
                                            LLM.setSession(target, session.withStreamAnchor(anchor)).andThen {
                                                Log.debug(
                                                    s"kyo-ai stream backend=${config.provider.name} model=${config.modelName} " +
                                                        s"mode=${if stringMode then "prefix" else "elements"} messages=${context.compacted.size} tools=${toolInfos.size}"
                                                ).andThen(completion.streamFragments(
                                                    config,
                                                    context,
                                                    resultSchema,
                                                    toolInfos,
                                                    usageSink
                                                )).map { fragments =>
                                                    Stream[A, LLM & Async & Scope & Abort[AIStreamException]] {
                                                        val consumed =
                                                            if stringMode then consumePrefixFragments(fragments, schema)
                                                            else consumeElementFragments(fragments, schema)
                                                        consumed.map { produced =>
                                                            // Recorded HERE, after the fold completes, so the turn
                                                            // joins the conversation exactly as a generated one and a
                                                            // later turn can read it; an abandoned or part-way-failed
                                                            // stream records nothing rather than half a turn. Recorded
                                                            // as the result_tool call the model made (the streamed
                                                            // fragments ARE its arguments), closed by a synthetic
                                                            // result: recording it as prose instead teaches a weaker
                                                            // model to answer in text on a later turn and pull away
                                                            // from the forced tool call a streamed result requires.
                                                            Kyo.when(produced.nonEmpty) {
                                                                val envelope =
                                                                    if stringMode then s"""{"resultValue":${Json.encode(produced)}}"""
                                                                    else s"""{"resultValue":$produced}"""
                                                                // The context grows with the conversation, so its
                                                                // size is a per-turn unique seed matching call to result.
                                                                val callId = CallId(s"stream-result-${context.compacted.size}")
                                                                val call   = Call(callId, Completion.resultToolName, envelope)
                                                                LLM.append(target, AssistantMessage("", Chunk(call)))
                                                                    .andThen(LLM.append(
                                                                        target,
                                                                        ToolMessage(callId, Json.encode("Result received."))
                                                                    ))
                                                            }.unit
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    end streamAgainst

    // Decodes the {resultValue: ...} envelope from a partial SSE buffer, completing it so the parsed-so-far
    // prefix decodes, and returns the resultValue sub-value (Absent if the prefix does not decode yet).
    private def resultValueOf(buf: String)(using Frame): Maybe[Structure.Value] =
        Json.decode[Structure.Value](completePartialJson(buf)).toMaybe.flatMap { record =>
            Structure.Path.field("resultValue").get(record).toMaybe.flatMap(_.headMaybe)
        }

    // Extracts the raw JSON substrings of the COMPLETE elements of the resultValue array from a partial SSE
    // buffer, tracking strings and brackets. An element is complete only when followed by a comma or the
    // array's closing bracket; an in-progress final element (no delimiter yet) is excluded. Never emits a
    // truncated tail and never drops a complete element before a trailing comma.
    private def completeElements(buf: String): Chunk[String] =
        @scala.annotation.tailrec
        def loop(i: Int, inString: Boolean, escaped: Boolean, depth: Int, arrayDepth: Int, elemStart: Int, acc: Chunk[String])
            : Chunk[String] =
            if i >= buf.length then acc
            else
                val c = buf.charAt(i)
                if inString then
                    if escaped then loop(i + 1, true, false, depth, arrayDepth, elemStart, acc)
                    else if c == '\\' then loop(i + 1, true, true, depth, arrayDepth, elemStart, acc)
                    else if c == '"' then loop(i + 1, false, false, depth, arrayDepth, elemStart, acc)
                    else loop(i + 1, true, false, depth, arrayDepth, elemStart, acc)
                else
                    val inArray = arrayDepth >= 0 && depth == arrayDepth
                    c match
                        case '[' if arrayDepth < 0 && depth == 1 =>
                            // entering the resultValue array; its elements live at this depth
                            loop(i + 1, false, false, depth + 1, depth + 1, -1, acc)
                        case '{' | '[' =>
                            loop(i + 1, false, false, depth + 1, arrayDepth, if inArray && elemStart < 0 then i else elemStart, acc)
                        case ']' if inArray =>
                            // the array closes: a started final element is complete
                            if elemStart >= 0 then acc.append(buf.substring(elemStart, i).trim) else acc
                        case '}' | ']' =>
                            loop(i + 1, false, false, depth - 1, arrayDepth, elemStart, acc)
                        case ',' if inArray =>
                            // the element before the comma is complete
                            val acc2 = if elemStart >= 0 then acc.append(buf.substring(elemStart, i).trim) else acc
                            loop(i + 1, false, false, depth, arrayDepth, -1, acc2)
                        case '"' =>
                            loop(i + 1, true, false, depth, arrayDepth, if inArray && elemStart < 0 then i else elemStart, acc)
                        case other =>
                            loop(
                                i + 1,
                                false,
                                false,
                                depth,
                                arrayDepth,
                                if inArray && elemStart < 0 && !other.isWhitespace then i else elemStart,
                                acc
                            )
                    end match
                end if
        loop(0, false, false, 0, -1, -1, Chunk.empty)
    end completeElements

    // Text mode (String): the provider exposes growing decodable prefixes of resultValue. Emit only the newly
    // decoded suffix so callers receive normal text chunks and can concatenate them into the final answer.
    // Fails if the stream ends with buffered args that never decoded.
    private[kyo] def consumePrefixFragments[A](
        fragments: Stream[String, Async & Scope & Abort[AIStreamException]],
        schema: Schema[A]
    )(using
        Frame,
        Tag[Emit[Chunk[A]]],
        Tag[Emit[Chunk[String]]]
    ): String < (Emit[Chunk[A]] & Async & Scope & Abort[AIStreamException]) =
        given Schema[A] = schema
        def emitText(delta: String): Unit < (Emit[Chunk[A]] & Abort[AIStreamException]) =
            Structure.decode[A](Structure.Value.Str(delta)) match
                case Result.Success(a) => Emit.value(Chunk(a))
                case Result.Failure(err) =>
                    Abort.fail(AIStreamDeltaException(s"stream[String] decoded text chunk failed schema validation: $err"))
                case Result.Panic(ex) =>
                    Abort.panic(ex)
        fragments.fold(("", Maybe.empty[String])) { (state, fragment) =>
            val (argsBuf, lastText) = state
            val newBuf              = argsBuf + fragment
            resultValueOf(newBuf) match
                case Present(Structure.Value.Str(text)) =>
                    lastText match
                        case Present(prev) if text == prev =>
                            Kyo.lift((newBuf, lastText))
                        case Present(prev) if text.startsWith(prev) =>
                            emitText(text.drop(prev.length)).andThen((newBuf, Present(text)))
                        case Present(prev) =>
                            Abort.fail(AIStreamDeltaException(
                                s"stream[String] decoded a non-monotonic text prefix. Previous: $prev, next: $text"
                            ))
                        case Absent =>
                            emitText(text).andThen((newBuf, Present(text)))
                case Present(other) =>
                    Abort.fail(AIStreamDeltaException(s"stream[String] expected a JSON string resultValue, got: ${Json.encode(other)}"))
                case Absent =>
                    Kyo.lift((newBuf, lastText))
            end match
        }.map { case (argsBuf, lastText) =>
            // A stream that ends without decoding a value failed, whether it buffered unusable bytes or
            // none. The empty case is reachable: a request the wire does not compel can be answered in
            // prose, producing no tool-call fragments, and reading that as success would return nothing to
            // a caller who asked for a value.
            if lastText.isEmpty then Abort.fail(AIStreamIncompleteException(argsBuf))
            // The text the model produced, handed back so the turn can be recorded from it.
            else Kyo.lift(lastText.getOrElse(""))
        }
    end consumePrefixFragments

    // Element mode (object by object): resultValue is an array of A. After each delta, emit every newly-complete
    // element (one whose JSON closed and is followed by a delimiter); an in-progress final element waits. Each A
    // is emitted exactly once, fully formed; a truncated tail is dropped and a complete element before a trailing
    // comma is kept.
    private[kyo] def consumeElementFragments[A](
        fragments: Stream[String, Async & Scope & Abort[AIStreamException]],
        schema: Schema[A]
    )(using
        Frame,
        Tag[Emit[Chunk[A]]],
        Tag[Emit[Chunk[String]]]
    ): String < (Emit[Chunk[A]] & Async & Scope & Abort[AIStreamException]) =
        given Schema[A] = schema
        def decodeElement(raw: String): A < Abort[AIStreamException] =
            Json.decode[Structure.Value](raw) match
                case Result.Success(v) =>
                    Structure.decode(v)(using schema, summon) match
                        case Result.Success(a) => a
                        case _                 => Abort.fail(AIStreamIncompleteException(raw))
                case _ => Abort.fail(AIStreamIncompleteException(raw))
        fragments.fold(("", 0)) { (state, fragment) =>
            val (argsBuf, emitted) = state
            val newBuf             = argsBuf + fragment
            val ready              = completeElements(newBuf)
            if ready.size <= emitted then Kyo.lift((newBuf, emitted))
            else Kyo.foreach(ready.drop(emitted))(decodeElement).map(as => Emit.value(as).andThen((newBuf, ready.size)))
        }.map { case (argsBuf, emitted) =>
            // Everything complete was emitted during the fold. An EMPTY array is a legitimate empty stream,
            // so the test is whether a resultValue arrived at all, not whether it held anything. A buffer
            // that never produced one is incomplete, including the empty buffer a prose-answered turn leaves.
            if emitted == 0 && resultValueOf(argsBuf).isEmpty then
                Abort.fail(AIStreamIncompleteException(argsBuf))
            // The elements the model produced, as the resultValue they arrived in.
            else Kyo.lift(resultValueOf(argsBuf).fold("")(Json.encode(_)))
        }
    end consumeElementFragments

    /** Discharges `LLM`, threading a fresh `State`. */
    def run[A, S](v: A < (LLM & S))(using Frame): A < (S & Async & Abort[AIGenException]) =
        Config.default.map(c => bracketed(State.empty(c))(v)((_, a) => a))

    /** Runs with a transformed configuration. */
    def run[A, S](f: Config => Config)(v: A < (LLM & S))(using Frame): A < (S & Async & Abort[AIGenException]) =
        Config.default.map(c => bracketed(State.empty(f(c)))(v)((_, a) => a))

    /** Runs with a specific configuration. */
    def run[A, S](config: Config)(v: A < (LLM & S))(using Frame): A < (S & Async & Abort[AIGenException]) =
        bracketed(State.empty(config))(v)((_, a) => a)

    /** Runs and returns the final `State` (transcripts) with the value. Internal/test use only. */
    private[kyo] def runTuple[A, S](v: A < (LLM & S))(using Frame): (LLM.State, A) < (S & Async & Abort[AIGenException]) =
        Config.default.map(c => bracketed(State.empty(c))(v)((s, a) => (s, a)))

    // Lifecycle: create the run-level preparation-interrupt registry, seat it in
    // the run env, and bracket the run so Sync.ensure interrupts every in-flight preparation fiber
    // on ANY exit (normal, abort, panic, interrupt), so no fiber leaks past the run.
    private def bracketed[A, S, B, S2](state: State)(v: A < (LLM & S))(
        done: (State, A) => B < S2
    )(using Frame): B < (S & S2 & Async & Abort[AIGenException]) =
        AtomicRef.init(Set.empty[Fiber[Unit, Any]]).map { registry =>
            val seated = state.copy(env = state.env.preparations(registry))
            Sync.ensure(Compactor.internal.interruptAll(registry))(runWith(seated)(v)(done))
        }

    // The `private[kyo]` effect surface `AI` is built on: the sole place `LLM`'s ArrowEffect ops are
    // summoned. Read = noun, write = setNoun, lifecycle/action = verb; every op targets exactly one instance.
    private inline def suspend[A](op: Op[A])(using Frame): A < LLM = ArrowEffect.suspend(Tag[LLM], op)

    private[kyo] def init(using Frame): AI < LLM              = suspend(Op.Init)
    private[kyo] def discard(ai: AI)(using Frame): Unit < LLM = suspend(Op.Discard(ai))

    private[kyo] def context(target: AI)(using Frame): Context < LLM                 = suspend(Op.Read(target))
    private[kyo] def setContext(target: AI, value: Context)(using Frame): Unit < LLM = suspend(Op.Set(target, value))
    private[kyo] def append(target: AI, message: Message)(using Frame): Unit < LLM   = suspend(Op.Add(target, message))

    private[kyo] def session(ai: AI)(using Frame): AISession < LLM                 = suspend(Op.GetSession(ai))
    private[kyo] def setSession(ai: AI, value: AISession)(using Frame): Unit < LLM = suspend(Op.SetSession(ai, value))

    private[kyo] def gen[A](target: AI, schema: Schema[A])(using Frame): A < LLM = suspend(Op.Gen[A](target, schema))

    private[kyo] def stream[A](target: AI, schema: Schema[A])(using
        Frame,
        Tag[Emit[Chunk[A]]]
    ): Stream[A, LLM & Async & Scope & Abort[AIStreamException]] < LLM =
        suspend(Op.Stream[A](target, schema, summon[Tag[Emit[Chunk[A]]]]))

    private[kyo] def env(using Frame): AIEnv < LLM                  = suspend(Op.Env)
    private[kyo] def setEnv(value: AIEnv)(using Frame): AIEnv < LLM = suspend(Op.SetEnv(value))

    /** Brackets a transform of the scope `AIEnv` over `v`: apply `f`, run `v`, restore the prior env on exit
      * (mirrors `Local.update`). The `enable` methods and `AI.withConfig` build on it.
      */
    private[kyo] def updateEnv[A, S](f: AIEnv => AIEnv)(v: A < (LLM & S))(using Frame): A < (LLM & S) =
        env.map(prev => setEnv(f(prev)).andThen(v).map(a => setEnv(prev).andThen(a)))
    private[kyo] def state(using Frame): State < LLM                 = suspend(Op.GetState)
    private[kyo] def setState(value: State)(using Frame): Unit < LLM = suspend(Op.SetState(value))

    /** The asymmetric isolate: per-key Context.merge on instances (fork-born added as-is), env keep-parent.
      * Keep = Async is the only Keep satisfying the in-tree parallel sites over a raw LLM row:
      * Async.fill/foreach/race require Isolate[LLM, Abort[E] & Async, LLM], and for the E=Nothing body it
      * reduces to Isolate[LLM, Async, LLM], which a wider Keep (Abort[Any] & Async) cannot satisfy by Keep
      * contravariance. runWith's residual Abort[AIGenException] is discharged inside isolate; an unrecovered
      * fork failure surfaces as a fiber panic.
      */
    given isolate: Isolate[LLM, Async, LLM] =
        new Isolate[LLM, Async, LLM]:
            type State        = LLM.State
            type Transform[A] = (LLM.State, A)
            def capture[A, S](f: State => A < S)(using Frame): A < (LLM & Async & S) =
                // GetState fires within the runWith handler loop, which answers with the live State.
                LLM.state.map(f)
            def isolate[A, S](state: State, v: A < (S & LLM))(using Frame): Transform[A] < (Async & S) =
                // Keep = Async carries Async, so the fork's Abort[AIGenException] is discharged here; a fork
                // that does not recover its own generation failure surfaces it as a fiber panic via
                // getOrThrow.
                Abort.run[AIGenException](LLM.runWith(state)(v)((s, a) => (s, a))).map(_.getOrThrow)
            def restore[A, S](v: Transform[A] < S)(using Frame): A < (LLM & S) =
                v.map { (forked, a) =>
                    Kyo.foreachDiscard(forked.instances.toMap.toSeq) { (ref, session) =>
                        // The instance may have been GC'd meanwhile; only merge slots whose AI is live.
                        val ai = ref.get()
                        if ai eq null then Kyo.unit else LLM.mergeInstance(ai, session.rawContext)
                    }.andThen(a)
                }

    private[kyo] def mergeInstance(ai: AI, forked: Context)(using Frame): Unit < LLM =
        ai.context.map { parent =>
            val merged = if parent.isEmpty then forked else parent.merge(forked)
            ai.setContext(merged)
        }

    // The compaction seam shared by eval (gen) and streamAgainst (stream): below the occupancy trigger the
    // context is re-served unchanged (NO render), so the provider prompt cache survives; at/above it the
    // compactor renders, the rebuilt compacted list is installed via ctx.copy(compacted = rebuilt) and written
    // back via setContext, and the updated Context is returned. raw is never shrunk. Absent compactor leaves
    // ctx unchanged. Both callers pass the compactor, config, and tool metadata resolved instance-over-scope,
    // so a named instance compacts identically on ai.gen and ai.stream.
    private def renderView(ai: AI, ctx: Context, config: Config, compactor: Maybe[Compactor[LLM]])(using
        Frame
    ): Context < (LLM & Async & Abort[AIGenException]) =
        compactor match
            case Present(c) if c.isDefault =>
                // The Default strategy owns the full occupancy / preparation / drift / eviction pipeline.
                // Usage-anchored occupancy; the boundary trigger is the effective high
                // watermark, min(highWatermark*window, contextCeiling). maxOutputTokens is
                // NOT part of occupancy: it is counted once on the hard-limit side inside render.
                val occupied = Compactor.internal.occupancy(ctx)
                LLM.env.map { env =>
                    LLM.session(ai).map { session =>
                        // The size-boundary render, shared by the occupancy trigger and a confirmed drift
                        // fire: ADOPT staged summaries and JOIN the fiber for this boundary's exact need,
                        // render + install the rebuilt compacted list, then bound raw's heap via evict.
                        // Pure eviction; the compacted view is untouched. raw is never shrunk.
                        def boundaryRender(triggered: Context, sess: AISession): Context < (LLM & Async & Abort[AIGenException]) =
                            Compactor.internal.Default.boundaryPrepare(ai, triggered, config, sess, env.preparations).map {
                                (prepared, session2) =>
                                    LLM.setSession(ai, session2).andThen {
                                        c.render(prepared).map { rebuilt =>
                                            val updated = prepared.copy(compacted = rebuilt)
                                            val evicted = Compactor.internal.Default.evict(updated, config)
                                            ai.setContext(evicted).andThen(evicted)
                                        }
                                    }
                            }
                        if occupied >= config.effectiveHigh then
                            // The boundary: tick the recall-decay clock, then render the filled state.
                            val ticked = ctx.withCompaction(ctx.compactionState.tickBoundary)
                            boundaryRender(ticked, session)
                        else
                            // Below the size boundary: measure relevance drift over the served
                            // view (model-free, over adopted + staged analyses). A CONFIRMED drift
                            // fires the SAME boundary machinery (occupied <= effectiveLow, so it sheds
                            // no size, only stale detail); otherwise arm the background fiber (prepare
                            // and/or drift causes) and serve the view unchanged (byte-stability).
                            Compactor.internal.Default.ensurePreparation(session).map { (prep, session1) =>
                                prep.staged.get.map { staged =>
                                    Tool.internal.infos.map { infos =>
                                        Compactor.internal.Default.driftDecision(
                                            ctx,
                                            config,
                                            infos,
                                            staged.analyses.toChunk.map(_._2)
                                        ) match
                                            case Compactor.internal.Default.DriftDecision.Fire =>
                                                // A confirmed drift is a boundary of the other cause; the
                                                // raw-retention backstop runs here too, inside boundaryRender.
                                                val fired = ctx.withCompaction(ctx.compactionState.tickBoundary.recordDriftFire)
                                                boundaryRender(fired, session1)
                                            case Compactor.internal.Default.DriftDecision.Arm =>
                                                // Latch pending and arm the drift cause, serving the view
                                                // unchanged. setSession seats the preparation, then
                                                // setContext installs the armed state last (setSession
                                                // carries the pre-arm context, so the order matters).
                                                val armedCtx = ctx.withCompaction(ctx.compactionState.armDrift)
                                                Compactor.internal.Default.armBelowBoundary(
                                                    ai,
                                                    armedCtx,
                                                    config,
                                                    session1,
                                                    env.preparations,
                                                    driftArm = true
                                                ).map {
                                                    session2 =>
                                                        LLM.setSession(ai, session2)
                                                            .andThen(ai.setContext(armedCtx))
                                                            .andThen(armedCtx)
                                                }
                                            case Compactor.internal.Default.DriftDecision.Idle =>
                                                if ctx.compactionState.driftPendingConfirm then
                                                    // A pending arm that no longer crosses (a return
                                                    // below the line): disarm the drift cause and clear
                                                    // pending, serving the view unchanged. setContext
                                                    // installs the cleared state after setSession.
                                                    val cleared = ctx.withCompaction(ctx.compactionState.disarmDrift)
                                                    Compactor.internal.Default.armBelowBoundary(
                                                        ai,
                                                        cleared,
                                                        config,
                                                        session1,
                                                        env.preparations,
                                                        driftArm = false
                                                    ).map {
                                                        session2 =>
                                                            LLM.setSession(ai, session2)
                                                                .andThen(ai.setContext(cleared))
                                                                .andThen(cleared)
                                                    }
                                                else
                                                    Compactor.internal.Default.armBelowBoundary(
                                                        ai,
                                                        ctx,
                                                        config,
                                                        session1,
                                                        env.preparations,
                                                        driftArm = false
                                                    ).map {
                                                        session2 => LLM.setSession(ai, session2).andThen(ctx)
                                                    }
                                                end if
                                        end match
                                    }
                                }
                            }
                        end if
                    }
                }
            case Present(c) =>
                // A non-Default enabled compactor (Compactor.none's raw pass-through or a user strategy)
                // owns its own view: NONE of Default's background preparation fiber, drift arming, or
                // eviction machinery runs (the off switch forks no fiber and issues no model call). The
                // framework still owns byte-stability for ANY implementation: below the occupancy trigger
                // the context is re-served unchanged (render is consulted only at a boundary, so the
                // provider prompt cache survives); at/above it the compactor's own render rebuilds the
                // compacted list, installed via setContext. For the pass-through this re-serves raw.
                if Compactor.internal.occupancy(ctx) < config.effectiveHigh then Kyo.lift(ctx)
                else
                    c.render(ctx).map { rebuilt =>
                        val updated = ctx.copy(compacted = rebuilt)
                        ai.setContext(updated).andThen(updated)
                    }
            case Absent => Kyo.lift(ctx)
    end renderView

    private def genLoop[A](ai: AI, schema: Schema[A])(using Frame): A < (LLM & Async & Abort[AIGenException]) =
        given Schema[A] = schema
        // The instance's enablements are layered onto the scope env for the eval, then restored. The
        // effective surface is the session's effectiveEnv (scope ++ instance ++ default guidance): ONE
        // construction shared with the faithful AISession.context enrichment, so transcript capture cannot
        // drift from generation. The eval threads `ai` explicitly, so no ambient current is needed.
        LLM.session(ai).map { session =>
            LLM.env.map { scopeEnv =>
                val merged = session.effectiveEnv(scopeEnv)
                LLM.setEnv(merged).map { prevEnv =>
                    AI.config.map { config =>
                        // One result tool + capture per generation: the capture accumulates the accepted
                        // value and rejection bookkeeping across iterations, so the exhaustion error can say
                        // what happened.
                        Thought.internal.infos.map { thoughts =>
                            Tool.internal.resultTool[A](thoughts).map { (resultToolInfos, capture) =>
                                def loop(iterations: Int): A < (LLM & Async & Abort[AIGenException]) =
                                    val forceResult = iterations >= config.maxIterations
                                    val step        = eval[A](ai, forceResult, resultToolInfos, capture)
                                    Mode.internal.handle(ai, step).map {
                                        case Present(r) => r
                                        case Absent     =>
                                            // A turn without a result is normal tool use below the iteration cap, so
                                            // the loop continues. Both backend families reach this: a command harness
                                            // can always answer without the result tool, and an HTTP backend does too
                                            // when it OFFERS rather than forces it. Once the result tool has been
                                            // forced (dropping user tools) and the turn STILL yields no result, eval
                                            // fed the failure back, so genLoop grants one informed repair turn past
                                            // maxIterations, then stops.
                                            if forceResult && iterations >= config.maxIterations + 1 then
                                                capture.rejections.map { (attempts, lastFailure) =>
                                                    val detail =
                                                        if attempts == 0 then Absent
                                                        else
                                                            Present(
                                                                s"the result tool was called and rejected $attempts time(s); " +
                                                                    s"last failure: ${lastFailure.getOrElse("see the conversation feedback")}"
                                                            )
                                                    Abort.fail(AIEvalExhaustedException(config.maxIterations, detail))
                                                }
                                            else
                                                AI.withConfig(c => c.seed(c.seed.map(_ * 31))) {
                                                    loop(iterations + 1)
                                                }
                                    }
                                end loop
                                // Restore the scope env on the failure path (the success path restores it
                                // below): otherwise the merged session enablements leak into every later
                                // generation of the same run.
                                Abort.recover[AIGenException] { e =>
                                    LLM.setEnv(prevEnv).andThen(Abort.fail(e))
                                }(loop(0))
                            }
                        }
                    }.map(a => LLM.setEnv(prevEnv).andThen(a))
                }
            }
        }
    end genLoop

    private def eval[A: Schema](
        ai: AI,
        forceResult: Boolean,
        resultToolInfos: Chunk[Tool.internal.Info[?, ?, LLM]],
        capture: Tool.internal.ResultCapture[A]
    )(using
        Frame
    ): Maybe[A] < (LLM & Async & Abort[AIGenException]) =
        for
            config   <- AI.config
            thoughts <- Thought.internal.infos
            env      <- LLM.env
            tools    <- if !forceResult then Tool.internal.infos else Kyo.lift(Chunk.empty)
            // The recall tool is registered here, bound to THIS calling instance, so a scope-wide compactor
            // serving many instances resolves each recall against its own state (never another session's).
            // Excluded when forcing the result, matching the user tools and the forced-turn directive below.
            recallInfos = if forceResult then Chunk.empty
            else env.compactor.map(c => c.tools(ai).flatMap(_.infos)).getOrElse(Chunk.empty)
            allTools     = tools ++ recallInfos ++ resultToolInfos
            resultSchema = Thought.internal.resultJson(thoughts, Json.jsonSchema[A])
            // Apply a pending stream re-anchor + the tokenizer-unit suffix stamp at the turn start (a prior
            // streaming turn seated its usage sink; gen consumes it here, the next handler-live point).
            // Idempotent when nothing is pending, so byte-stability is preserved. A transport failure while
            // measuring classifies to a typed leaf, the same treatment the completion call gets below.
            _ <- Abort.recover[HttpException](e => Abort.fail(Completion.classifyHttp(config, e)))(
                Compactor.internal.applyStreamMeasure(ai, config)
            )
            ctx <- ai.context
            // Compaction seam (shared with streamAgainst via renderView): the framework owns byte-stability.
            // Below the occupancy trigger re-serve ctx unchanged (NO render); at/above it render + install the
            // rebuilt compacted list, write it back via setContext, and send it. raw is never shrunk.
            view <- renderView(ai, ctx, config, env.compactor)
            // The forced turn carries an explicit finalize directive, request-scoped (never stored) and built
            // ON THE VIEW so the anchor basis stays the pre-directive view.compacted. On a backend that compels
            // the call (forced tool_choice with grammar-constrained decoding), the model must emit a schema-valid
            // envelope even when it believes it was told to keep working, and without an explicit instruction it
            // sometimes encodes that withholding INTO the envelope (a progress note or empty string in place of
            // the value). The eval loop accepts any schema-valid result, so a compelled backend has no repair
            // turn; the directive closes that gap at the source. It also names itself as the instruction to
            // finalize and retires any standing "keep working" order the caller left.
            requestCtx =
                if forceResult then
                    view.systemMessage(
                        s"This is the instruction to finalize. Every other tool has been REMOVED for this " +
                            s"turn: '${Completion.resultToolName}' is the only tool available, and a call to any " +
                            "other tool will fail. Any earlier instruction to keep working, wait, or withhold the " +
                            s"result no longer applies. Produce your final result now by calling " +
                            s"'${Completion.resultToolName}'. Base every field on the conversation above, copying " +
                            "exact values from prior tool results; do not report progress, interim state, or " +
                            "placeholder values."
                    )
                else view
            context <- Prompt.internal.enrichedContext(requestCtx, allTools)
            _ <- Log.debug(
                // Carries the facts that DECIDE this request's shape, not just its size: the reasoning state,
                // resolved amount, and ceiling are each derived from a declaration, so a turn that behaved
                // unexpectedly can't be diagnosed from the call alone without this.
                s"kyo-ai gen backend=${config.provider.name} model=${config.modelName} " +
                    s"messages=${context.compacted.size} tools=${allTools.size} thoughts=${thoughts.size} " +
                    s"forceResult=$forceResult reasoning=${config.reasoningEnabled} encoding=${config.modelReasoning} " +
                    s"amount=${config.resolvedAmount} ceiling=${config.effectiveMaxOutputTokens}"
            )
            // A reasoning statement the entry's wire cannot express is reported once per generation, then
            // dropped. It belongs here, not in the four backends: the mismatch is between what the caller
            // stated and what the entry declares, which every backend would otherwise detect identically.
            _                <- config.reasoningMismatch.fold(Kyo.unit)(warning => Log.warn(s"kyo-ai $warning"))
            replyAndRepaired <-
                // The configured timeout is this call's deadline and covers its retries, so a slow transient
                // that retries cannot carry the call past it. The transport install below keeps an attempt's
                // own deadline, matching kyo-http's total-operation timeout semantics.
                Async.timeoutWithError[AIGenException, Result[AIGenException, Completion.Reply], LLM](
                    config.timeout,
                    Result.Failure(AICompletionTimeoutException(config.provider.name, config.timeout))
                ) {
                    // The completion's own failure rides the value channel as a Result across the deadline's
                    // fiber boundary and is re-raised below, so a typed leaf stays typed and only the deadline
                    // itself uses the error channel.
                    Abort.run[AIGenException] {
                        HttpClient.withConfig(_.timeout(config.timeout)) {
                            Abort.run[Closed] {
                                config.provider
                                    .completion(config, context, allTools, Present(resultSchema))
                                    .handle(
                                        // One retry policy for both backend families. A raw HttpException is
                                        // classified into the module's typed leaves BEFORE the retry clause
                                        // sees it, so the clause names AITransientException alone: transport
                                        // blips, transient outages, and throttles retry; auth failures,
                                        // timeouts, and rejected requests surface without retry. Command
                                        // harnesses classify into the same leaves.
                                        Abort.recover[HttpException](e =>
                                            Abort.fail(Completion.classifyHttp(config, e))
                                        )(_),
                                        config.meter.run,
                                        Retry[AITransientException](config.retrySchedule)(_)
                                    )
                            }.map {
                                case Result.Success(r) => r
                                case Result.Failure(_) => Abort.panic(AIMeterClosedException())
                                case Result.Panic(ex)  => Abort.panic(ex)
                            }
                        }
                    }
                }.map {
                    // A wire that refuses the model's tool call rather than returning it never lets the turn
                    // reach the loop, so one bad sample would fail a well-formed ask outright. Feeding an
                    // empty turn forward puts it back on the loop's path: the model is told what went wrong
                    // and maxIterations bounds the attempts, so a wire rejecting every one ends in the same
                    // exhaustion any unproductive loop reaches. The detail is logged rather than dropped,
                    // since the exhaustion further down cannot say a rejection was really a bad request.
                    case Result.Failure(rejected: AIToolCallRejectedException) =>
                        // Feed the ENDPOINT's own reason forward, not a generic line: telling the model why
                        // ("Failed to parse tool call arguments as JSON") is the point of the repair turn.
                        // `rejectionRepaired` guards the forced-turn finalize message below so one failure
                        // gets one feedback message, not two.
                        Log.warn(s"kyo-ai ${rejected.getMessage}").andThen {
                            ai.updateContext(_.systemMessage(
                                s"Your previous tool call was rejected: ${rejected.detail}. Produce a valid " +
                                    "tool call whose arguments are well-formed JSON matching the schema exactly."
                            )).andThen((Completion.Reply(Chunk.empty, Completion.StopReason.Completed), true))
                        }
                    case other => Abort.get(other).map(r => (r, false))
                }
            (reply, rejectionRepaired) = replyAndRepaired
            messages                   = reply.messages
            // A ceiling stop is reported by the backend and adjudicated here, because neither layer alone
            // can tell a usable turn from a truncated one. A reply that stopped at the ceiling may still
            // carry a complete call worth running; what makes it unusable is having nothing to act on, or
            // its LAST call cut off mid-argument. Only the last call can be cut (nothing follows a
            // truncation); an earlier call that fails to decode is the model's error, not the ceiling's.
            // This sits here, not in a backend, because reading the payload is the tool loop's job: a wire
            // decode guessing at usability would fail working turns, or let a truncated call through to be
            // reported as a schema problem many rejections later.
            _ <- Kyo.when(reply.stopReason == Completion.StopReason.MaxOutputTokens) {
                val stopped = messages.collect { case msg: AssistantMessage => msg.calls }.flatten
                val unusable =
                    stopped.isEmpty ||
                        stopped.lastOption.exists(call => Json.decode[Structure.Value](call.arguments).isFailure)
                Kyo.when(unusable) {
                    // Carry what came before: a run seen producing two complete results this loop rejected
                    // (regenerating each time) only reached the ceiling on the third attempt. Reporting the
                    // ceiling alone would hide that cause.
                    capture.rejections.map { (attempts, lastFailure) =>
                        val prior =
                            if attempts == 0 then Absent
                            else
                                Present(
                                    s"the result tool was called and rejected $attempts time(s); " +
                                        s"last failure: ${lastFailure.getOrElse("see the conversation feedback")}"
                                )
                        Abort.fail(AIOutputLimitException(
                            config.provider.name,
                            config.modelName,
                            Present(config.effectiveMaxOutputTokens),
                            prior,
                            reply.reasoningTokens
                        ))
                    }
                }
            }
            _ <- Log.debug(
                s"kyo-ai gen backend=${config.provider.name} returned messages=${messages.size} " +
                    s"toolCalls=${messages.collect { case msg: AssistantMessage => msg.calls.size }.sum}"
            )
            _ <- ai.updateContext(ctx => messages.foldLeft(ctx)(_.add(_)))
            // Re-anchor occupancy on the provider's reported request total through the ONE fused seam
            // helper every usage-consumption site shares, so the anchor scalar and the per-message stamps it
            // covers can never disagree: it records the exact input-token total at the sent view's size (so
            // the next pass sizes only the suffix appended since) AND apportions the exact sent view via the
            // active tokenizer, propagating each stamp onto its raw twin for the demotion loop. The sent view
            // is the pre-response request (view.compacted), never the just-appended response tail. Absent
            // usage leaves the anchor untouched (offline-estimated).
            _ <- reply.usage match
                case Present(u) =>
                    val (tokenizer, tokenizerId) = Compactor.internal.activeTokenizer(config)
                    Abort.recover[HttpException](e => Abort.fail(Completion.classifyHttp(config, e)))(
                        ai.context.map(c =>
                            Compactor.internal.reanchor(c, view.compacted, u.inputTokens, tokenizer, tokenizerId)
                                .map(ai.setContext)
                        )
                    )
                case Absent => Kyo.lift(())
            calls            = messages.collect { case msg: AssistantMessage => msg.calls }.flatten
            completedCallIds = messages.collect { case ToolMessage(callId, _, _, _) => callId }
            // The payload is decoded ONCE by the tool loop against the result tool's typed schema, like any
            // other tool; the capturing run interprets the decoded envelope (thought hooks, open-shape
            // conformance) and stores the value. Rejections ride the tool loop's feedback, so eval never
            // touches the payload or adds a parallel repair channel.
            preRejections <- capture.rejections
            _             <- Tool.internal.handle(ai, allTools, calls.filterNot(call => completedCallIds.contains(call.id)))
            r <- capture.value.map {
                case present @ Present(_) => Kyo.lift(present)
                case Absent =>
                    val calledResult = calls.exists(_.function == Completion.resultToolName)
                    val repair =
                        if calledResult then
                            // The call was dispatched and rejected; the tool loop already fed the reason
                            // back. Record the rejection for the exhaustion report when a decode-stage
                            // rejection never reached the capturing run.
                            capture.rejections.map { (attempts, _) =>
                                Kyo.when(attempts == preRejections._1)(
                                    capture.rejected("the result payload did not decode against the required schema")
                                ).unit
                            }
                        else if forceResult && !rejectionRepaired then
                            // No result call at all on a forced turn: the model dodged the tool. Feed the
                            // failure back so genLoop's one repair turn is informed rather than blind. This
                            // is the fallback for any backend that cannot compel the call (HTTP backends
                            // forcing it never reach here; a command harness does). Skipped when the turn was
                            // already a rejection repair, so one failure gets one feedback message, and this
                            // schema message would be wrong there since user tools still ride that turn.
                            ai.updateContext(_.systemMessage(
                                "You have not produced a result. This is the instruction to finalize, and any " +
                                    "earlier instruction to keep working or withhold the result no longer applies. " +
                                    "Produce your final result now; it must match this schema exactly: " +
                                    s"${Json.encode(resultSchema)}"
                            )).unit
                        else Kyo.unit
                    repair.andThen(Kyo.lift(Maybe.empty[A]))
            }
        yield r
        end for
    end eval

    private[kyo] object internal:

        /** The op GADT. `ArrowEffect[internal.Op, Id]` indexes each op's reply by `A`, so the handler
          * continuation needs no reply-side cast. Field-less ops are `case object`s; the rest carry data.
          */
        abstract class Op[A]
        object Op:
            case class Read(target: AI)                      extends Op[Context]
            case class Add(target: AI, message: Message)     extends Op[Unit]
            case class Set(target: AI, context: Context)     extends Op[Unit]
            case object Init                                 extends Op[AI]
            case object Env                                  extends Op[AIEnv]
            case class Gen[A](target: AI, schema: Schema[A]) extends Op[A]
            case class Stream[A](target: AI, schema: Schema[A], emitTag: Tag[Emit[Chunk[A]]])
                extends Op[kyo.Stream[A, LLM & Async & Scope & Abort[AIStreamException]]]
            case class SetEnv(env: AIEnv)                         extends Op[AIEnv]
            case class Discard(target: AI)                        extends Op[Unit]
            case object GetState                                  extends Op[LLM.State]
            case class SetState(state: LLM.State)                 extends Op[Unit]
            case class GetSession(target: AI)                     extends Op[AISession]
            case class SetSession(target: AI, session: AISession) extends Op[Unit]
        end Op

        /** A `WeakReference` to an `AI`, used as the `State` map key so a dropped `AI` becomes reclaimable.
          * Equality and hash are by the AI's stable `id`, so a slot matches its key even after the referent is
          * GC'd, letting the sweep (`State.pruned`) find and drop it. `isValid` is false once collected.
          */
        final class AIRef(ai: AI) extends WeakReference[AI](ai):
            private val refId: Long    = ai.id
            def isValid: Boolean       = get() != null
            override def hashCode: Int = refId.hashCode
            override def equals(o: Any): Boolean = o match
                case r: AIRef => refId == r.refId
                case _        => false
        end AIRef

    end internal

end LLM
