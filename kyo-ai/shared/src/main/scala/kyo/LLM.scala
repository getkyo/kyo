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
  * `LLM` is a custom `ArrowEffect` whose ops carry data: a program typed `< LLM` is a composed tree of
  * virtual operations with no `Async` in its row. The ops read and append to per-instance conversation
  * histories held in one threaded `State`. The single op that reaches the world is `Gen`, whose handler
  * interpretation runs the eval loop; that is where `Async` and `Abort[AIGenException]` enter, riding out
  * on `run`'s residual. `AI` is the first-class instance value: an identity for one conversation slot.
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

    /** The one threaded record: per-instance histories and enablements (keyed by a `WeakReference` to the
      * `AI`, so dropped instances are reclaimable), the monotonic id counter the `Init` op draws from, an
      * `owner` token identifying this run (every instance is stamped with it, so a cross-run use is detectable),
      * and the scope env bundle.
      */
    final case class State private[kyo] (
        instances: Dict[internal.AIRef, AISession],
        nextId: Long,
        owner: AnyRef,
        env: AIEnv
    ):
        private[kyo] def sessionOf(ai: AI): AISession             = instances.get(ai.ref).getOrElse(AISession.empty)
        private[kyo] def contextOf(ai: AI): Context               = sessionOf(ai).context
        private[kyo] def withSession(ai: AI, s: AISession): State = copy(instances = instances.update(ai.ref, s))
        private[kyo] def withContext(ai: AI, ctx: Context): State = withSession(ai, sessionOf(ai).copy(context = ctx))
        private[kyo] def without(ai: AI): State                   = copy(instances = instances.remove(ai.ref))
        // Drops every slot whose AI has been GC'd; run when minting a new instance so an unbounded mint
        // stream never accumulates dead slots.
        private[kyo] def pruned: State = copy(instances = instances.filter((ref, _) => ref.isValid))
    end State

    private[kyo] object State:
        def empty(config: Config): State =
            // A fresh owner per run (object identity, no counter); every instance minted in this run carries it.
            State(Dict.empty, 0L, new AnyRef, AIEnv(Present(config), Prompt.empty, Chunk.empty, Chunk.empty, Chunk.empty))
    end State

    // If an op targets an instance created by a DIFFERENT run (its owner differs from this run's), the
    // failure to raise: that instance can't address this run's slots, so fail loud (pointing at
    // snapshot/recover) instead of silently reading or writing a same-id but wrong entry.
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
                        // An instance from a different LLM.run fails loud here (crossRunFailure) instead of
                        // silently addressing this run's same-id slot. The panic is the arm's result so it
                        // rides runWith's residual Abort row (not the LLM continuation), aborting the whole
                        // computation.
                        case _ if crossRunFailure(input, state).nonEmpty =>
                            Abort.panic(crossRunFailure(input, state).get)
                        case op: Op.Read =>
                            Loop.continue(state, cont(state.contextOf(op.target)))
                        case op: Op.Add =>
                            Loop.continue(state.withContext(op.target, state.contextOf(op.target).add(op.message)), cont(()))
                        case op: Op.Set =>
                            Loop.continue(state.withContext(op.target, op.context), cont(()))
                        case _: Op.Init.type =>
                            // Mint the instance with the next id from the threaded State (no global counter)
                            // and stamp it with this run's owner; bump the counter, and prune GC'd slots so an
                            // unbounded mint stream never accumulates.
                            val ai = new AI(state.nextId, state.owner)
                            Loop.continue(state.pruned.copy(nextId = state.nextId + 1).withContext(ai, Context.empty), cont(ai))
                        case _: Op.Env.type =>
                            Loop.continue(state, cont(state.env))
                        case op: Op.SetEnv =>
                            Loop.continue(state.copy(env = op.env), cont(state.env))
                        case op: Op.Discard =>
                            Loop.continue(state.without(op.target), cont(()))
                        case _: Op.GetState.type =>
                            Loop.continue(state, cont(state))
                        case op: Op.SetState =>
                            // Restoring a snapshot must never lower the id counter: ids stay monotonic within a
                            // run so a slot key is never reused (a forget/fresh that rolls instances back keeps
                            // the high-water id).
                            Loop.continue(op.state.copy(nextId = math.max(state.nextId, op.state.nextId)), cont(()))
                        case op: Op.GetSession =>
                            Loop.continue(state, cont(state.sessionOf(op.target)))
                        case op: Op.SetSession =>
                            Loop.continue(state.withSession(op.target, op.session), cont(()))
                        case op: Op.Gen[C] @unchecked =>
                            // Gen interpretation: the one op that reaches the world, runs the eval loop. The
                            // eval loop is itself an LLM computation (it reads config, appends replies, runs
                            // tools), so a nested runWith against the live state discharges those LLM ops and
                            // threads the updated state back; Async & Abort[HttpException] enter here and ride
                            // out on run's residual.
                            runWith(state)(genLoop(op.target, op.schema))((s, c) => (s, c))
                                .map((s, c) => Loop.continue(s, cont(c)))
                        case op: Op.Stream[C] @unchecked =>
                            // Stream interpretation: the SSE projection is itself an LLM computation (it reads
                            // config and assembles the result-tool/context), so a nested runWith against the live
                            // state discharges those LLM ops. At an update boundary the projection writes the
                            // rendered compacted list back through the compaction seam (setContext), so the
                            // threaded state carries that update out (Loop.continue(s, ...)). The op carries the
                            // element-emit Tag captured at the suspend site (the handler's C is abstract, so the
                            // Tag cannot be re-derived here).
                            runWith(state)(streamAgainst(op.target, op.schema)(using summon[Frame], op.emitTag))((s, c) => (s, c))
                                .map((s, c) => Loop.continue(s, cont(c)))
            ,
            done = (state, a) => done(state, a)
        )
    end runWith

    /** Projects the conversation as a streaming completion: posts the SSE request, parses each delta as an
      * OpenAI streaming chunk, accumulates the tool-call argument fragments, and emits decoded values. For
      * `String`, emitted chunks concatenate to the final text. For every other type, emitted values are complete
      * array elements. The result_tool rides every request so the model has a tool to call. Config and the
      * result-tool/context assembly are read on the `LLM` row; the returned `Stream` value carries the I/O
      * effects in its element row, with the
      * SSE connection scoped so it closes on stream termination or error. The returned `Stream` carries its
      * failures typed in its row (`Abort[AIStreamException]`): a malformed delta is an
      * `AIStreamDeltaException`, a stream that ends without a decodable value an `AIStreamIncompleteException`,
      * a transport failure an `AITransportException` wrapping the `HttpException`. A missing API key is the
      * one failure raised eagerly (before the `Stream` value), so it rides the run boundary as an
      * `AIMissingApiKeyException` (an `AIGenException`).
      */
    /** Closes the open string (if any) and balances open brackets in a partial JSON buffer, so a streaming
      * prefix decodes into the value parsed so far. A prefix that ends mid-token (mid-number, mid-keyword)
      * still fails the subsequent decode and is retried as more data arrives.
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

    private[kyo] def streamAgainst[A](target: AI, schema: Schema[A])(using
        Frame,
        Tag[Emit[Chunk[A]]]
    ): Stream[A, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        given Schema[A] = schema
        // Resolve config, the compactor, and tool metadata the SAME way genLoop resolves them for gen
        // (instance-over-scope), so `ai.stream` compacts identically to `ai.gen` for a named instance: the
        // merged env means render's occupancy/config read and its superKeys (Tool.internal.infos) see the
        // instance's overrides. Prompt/thoughts/modes stay scope-only (the stream path sends only the result
        // tool and never loops them), unchanged from before.
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
                        // assemble the streaming request with the result_tool in every request:
                        // resultToolInfo supplies the tool definition; enrichedContext includes its prompt and
                        // the tool definition in the request body.
                        val toolInfos = Tool.internal.resultToolInfo.infos
                        // Two streaming modes, inferred from A. A String result streams incrementally: each emitted
                        // element is the next decoded text chunk. Any other type streams object by object: the model
                        // returns an array of A and each element is emitted once it is complete (never a half-filled A).
                        // A bare partial scalar has no meaning, so only String takes the incremental text path;
                        // everything else, scalars included, takes the complete-element path.
                        val stringMode = schema.structure match
                            case Structure.Type.Primitive(Structure.PrimitiveKind.String, _) => true
                            case _                                                           => false
                        context(target).map { targetContext =>
                            // Compaction seam for the stream path, shared with gen via renderView: below the
                            // occupancy trigger re-serve the context unchanged, at/above it render + install the
                            // rebuilt compacted list. The compactor is read from the merged env (instance-over-scope).
                            LLM.env.map(e => renderView(target, targetContext, config, e.compactor))
                                .map(Prompt.internal.enrichedContext(_, toolInfos))
                                .map { context =>
                                    // Wrap in the {resultValue: A} object envelope (an array of A in element mode); a bare
                                    // non-object schema is rejected by the providers, which require an object tool schema. The
                                    // array schema is derived through kyo-schema's chunk Schema, not hand-built.
                                    val resultValueSchema =
                                        if stringMode then Json.jsonSchema[A] else Json.jsonSchema(using Schema.chunkSchema(using schema))
                                    val resultSchema = Thought.internal.resultJson(Chunk.empty, resultValueSchema)
                                    val completion   = config.provider.completion
                                    Log.debug(
                                        s"kyo-ai stream backend=${config.provider.name} model=${config.modelName} " +
                                            s"mode=${if stringMode then "prefix" else "elements"} messages=${context.compacted.size} tools=${toolInfos.size}"
                                    ).andThen(completion.streamFragments(config, context, resultSchema, toolInfos)).map { fragments =>
                                        Stream[A, Async & Scope & Abort[AIStreamException]] {
                                            if stringMode then consumePrefixFragments(fragments, schema)
                                            else consumeElementFragments(fragments, schema)
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
    // buffer, walking it with string and bracket tracking. An element is complete only when it is followed by a
    // comma or the array's closing bracket; an in-progress final element (no delimiter yet) is excluded. This
    // never emits a truncated tail and never drops a complete element sitting before a trailing comma, both of
    // which a force-close-then-decode-the-whole-array approach gets wrong.
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
    ): Unit < (Emit[Chunk[A]] & Async & Scope & Abort[AIStreamException]) =
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
            // No provider terminator is required: if the SSE stream ends having buffered argument JSON but
            // never emitted a decodable A, the generation failed.
            if lastText.isEmpty && argsBuf.nonEmpty then Abort.fail(AIStreamIncompleteException(argsBuf))
            else Kyo.lift(())
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
    ): Unit < (Emit[Chunk[A]] & Async & Scope & Abort[AIStreamException]) =
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
            // Everything complete was emitted during the fold. An empty or in-progress-only array yields an empty
            // stream; only a buffer that never produced a resultValue array at all is incomplete.
            if emitted == 0 && argsBuf.nonEmpty && resultValueOf(argsBuf).isEmpty then
                Abort.fail(AIStreamIncompleteException(argsBuf))
            else Kyo.unit
        }
    end consumeElementFragments

    /** Discharges `LLM`, threading a fresh `State`. */
    def run[A, S](v: A < (LLM & S))(using Frame): A < (S & Async & Abort[AIGenException]) =
        Config.default.map(c => runWith(State.empty(c))(v)((_, a) => a))

    /** Runs with a transformed configuration. */
    def run[A, S](f: Config => Config)(v: A < (LLM & S))(using Frame): A < (S & Async & Abort[AIGenException]) =
        Config.default.map(c => runWith(State.empty(f(c)))(v)((_, a) => a))

    /** Runs with a specific configuration. */
    def run[A, S](config: Config)(v: A < (LLM & S))(using Frame): A < (S & Async & Abort[AIGenException]) =
        runWith(State.empty(config))(v)((_, a) => a)

    /** Runs and returns the final `State` (transcripts) with the value. Internal/test use only. */
    private[kyo] def runTuple[A, S](v: A < (LLM & S))(using Frame): (LLM.State, A) < (S & Async & Abort[AIGenException]) =
        Config.default.map(c => runWith(State.empty(c))(v)((s, a) => (s, a)))

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
    ): Stream[A, Async & Scope & Abort[AIStreamException]] < LLM =
        suspend(Op.Stream[A](target, schema, summon[Tag[Emit[Chunk[A]]]]))

    private[kyo] def env(using Frame): AIEnv < LLM                  = suspend(Op.Env)
    private[kyo] def setEnv(value: AIEnv)(using Frame): AIEnv < LLM = suspend(Op.SetEnv(value))

    /** Brackets a transform of the scope `AIEnv` over `v`: applies `f` to the current env, runs `v`, then
      * restores the prior env on exit (mirrors `Local.update`). The `enable` methods and `AI.withConfig`
      * build on it, so the get/modify/set/restore is written once.
      */
    private[kyo] def updateEnv[A, S](f: AIEnv => AIEnv)(v: A < (LLM & S))(using Frame): A < (LLM & S) =
        env.map(prev => setEnv(f(prev)).andThen(v).map(a => setEnv(prev).andThen(a)))
    private[kyo] def state(using Frame): State < LLM                 = suspend(Op.GetState)
    private[kyo] def setState(value: State)(using Frame): Unit < LLM = suspend(Op.SetState(value))

    /** The asymmetric isolate: per-key Context.merge on instances (fork-born added as-is), env keep-parent.
      * Keep = Async is the only Keep that satisfies the in-tree parallel sites over a raw LLM row:
      * Async.fill/foreach/race require Isolate[LLM, Abort[E] & Async, LLM], and for the E=Nothing body
      * (a parallel sampling mode's gen, whose transport errors the eval loop already recovers) that reduces to
      * Isolate[LLM, Async, LLM], which a wider Keep (Abort[Any] & Async) cannot satisfy by Keep
      * contravariance. runWith's residual Abort[AIGenException] is discharged inside isolate; an unrecovered
      * fork failure surfaces as a fiber panic on any unrecovered fork.
      */
    given isolate: Isolate[LLM, Async, LLM] =
        new Isolate[LLM, Async, LLM]:
            type State        = LLM.State
            type Transform[A] = (LLM.State, A)
            def capture[A, S](f: State => A < S)(using Frame): A < (LLM & Async & S) =
                // GetState fires within the runWith handler loop, which answers with the live State.
                LLM.state.map(f)
            def isolate[A, S](state: State, v: A < (S & LLM))(using Frame): Transform[A] < (Async & S) =
                // runWith's residual is < (S & Async & Abort[AIGenException]); Keep = Async carries Async, so
                // the fork's Abort[AIGenException] is discharged here. A fork that does not recover its own
                // generation failure surfaces it as a fiber panic via getOrThrow, consistent with an
                // unrecoverable concurrent branch failure.
                Abort.run[AIGenException](LLM.runWith(state)(v)((s, a) => (s, a))).map(_.getOrThrow)
            def restore[A, S](v: Transform[A] < S)(using Frame): A < (LLM & S) =
                v.map { (forked, a) =>
                    Kyo.foreachDiscard(forked.instances.toMap.toSeq) { (ref, session) =>
                        // The instance may have been GC'd meanwhile; only merge slots whose AI is still live.
                        val ai = ref.get()
                        if ai eq null then Kyo.unit else LLM.mergeInstance(ai, session.context)
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
            case Present(c) =>
                // Usage-anchored occupancy (§5a); the boundary trigger is the effective high
                // watermark (§4, §6), min(highWatermark*window, contextCeiling). maxOutputTokens is
                // NOT part of occupancy: it is counted once on the hard-limit side inside render (§7).
                val occupied = Compactor.internal.occupancy(ctx)
                if occupied >= config.effectiveHigh then
                    // A boundary of either cause ticks the recall-decay clock (§5e), then renders and
                    // installs the rebuilt compacted list. raw is never shrunk.
                    val ticked = ctx.withCompaction(ctx.compactionState.tickBoundary)
                    c.render(ticked).map { rebuilt =>
                        val updated = ticked.copy(compacted = rebuilt)
                        ai.setContext(updated).andThen(updated)
                    }
                else Kyo.lift(ctx)
                end if
            case Absent => Kyo.lift(ctx)
    end renderView

    private def genLoop[A](ai: AI, schema: Schema[A])(using Frame): A < (LLM & Async & Abort[AIGenException]) =
        given Schema[A] = schema
        // The instance's own enablements (added via ai.enable) are layered onto the scope env for the
        // duration of the eval, then restored, so the effective surface is `scope ++ instance`. The eval
        // threads `ai` explicitly (reads ai.context, appends to ai), so no ambient current is needed.
        LLM.session(ai).map { session =>
            LLM.env.map { scopeEnv =>
                val merged = scopeEnv
                    .copy(config = session.env.config.orElse(scopeEnv.config))
                    .copy(compactor = session.env.compactor.orElse(scopeEnv.compactor))
                    .addPrompt(session.env.prompt)
                    .addTools(session.env.tools)
                    .addThoughts(session.env.thoughts)
                    .addModes(session.env.mode)
                LLM.setEnv(merged).map { prevEnv =>
                    AI.enable(Prompt.internal.defaultGuidance) {
                        AI.config.map { config =>
                            def loop(iterations: Int): A < (LLM & Async & Abort[AIGenException]) =
                                val step =
                                    Abort.recover[HttpException](e => Abort.fail(AITransportException(e)))(
                                        eval[A](ai, iterations >= config.maxIterations)
                                    )
                                Mode.internal.handle(ai, step).map {
                                    case Present(r) => r
                                    case Absent =>
                                        if iterations >= config.maxIterations * 2 then
                                            Abort.fail(AIEvalExhaustedException(config.maxIterations))
                                        else
                                            AI.withConfig(c => c.seed(c.seed.map(_ * 31))) {
                                                loop(iterations + 1)
                                            }
                                }
                            end loop
                            loop(0)
                        }
                    }.map(a => LLM.setEnv(prevEnv).andThen(a))
                }
            }
        }
    end genLoop

    private def eval[A: Schema](ai: AI, forceResult: Boolean)(using
        Frame
    ): Maybe[A] < (LLM & Async & Abort[HttpException | AIGenException]) =
        for
            config   <- AI.config
            thoughts <- Thought.internal.infos
            env      <- LLM.env
            tools    <- if !forceResult then Tool.internal.infos else Kyo.lift(Chunk.empty)
            // The recall tool is registered here, bound to THIS calling instance, so a scope-wide compactor
            // serving many instances resolves each recall against its own state (never another session's).
            // Excluded when forcing the result, matching the user tools.
            recallInfos = if forceResult then Chunk.empty
            else env.compactor.map(c => c.tools(ai).flatMap(_.infos)).getOrElse(Chunk.empty)
            resultTool   = Tool.internal.resultToolInfo
            allTools     = tools ++ recallInfos ++ resultTool.infos
            resultSchema = Thought.internal.resultJson(thoughts, Json.jsonSchema[A])
            ctx <- ai.context
            // Compaction seam (shared with streamAgainst via renderView): the framework owns byte-stability.
            // Below the occupancy trigger re-serve ctx unchanged (NO render); at/above it render + install the
            // rebuilt compacted list, write it back via setContext, and send it. raw is never shrunk.
            view    <- renderView(ai, ctx, config, env.compactor)
            context <- Prompt.internal.enrichedContext(view, allTools)
            _ <- Log.debug(
                s"kyo-ai gen backend=${config.provider.name} model=${config.modelName} " +
                    s"messages=${context.compacted.size} tools=${allTools.size} thoughts=${thoughts.size} forceResult=$forceResult"
            )
            completion <-
                HttpClient.withConfig(_.timeout(config.timeout)) {
                    Abort.run[Closed] {
                        config.provider
                            .completion(config, context, allTools, Present(resultSchema))
                            .handle(
                                config.meter.run,
                                Retry[HttpException](config.retrySchedule)(_)
                            )
                    }.map {
                        case Result.Success(res) => res
                        case Result.Failure(_)   => Abort.panic(AIMeterClosedException())
                        case Result.Panic(ex)    => Abort.panic(ex)
                    }
                }
            messages = completion.messages
            _ <- Log.debug(
                s"kyo-ai gen backend=${config.provider.name} returned messages=${messages.size} " +
                    s"toolCalls=${messages.collect { case msg: AssistantMessage => msg.calls.size }.sum}"
            )
            _ <- ai.updateContext(ctx => messages.foldLeft(ctx)(_.add(_)))
            // Re-anchor occupancy on the provider's reported request total (§5a) through the ONE fused seam
            // helper every usage-consumption site shares, so the anchor scalar and the per-message stamps it
            // covers can never disagree: it records the exact input-token total at the sent view's size (so
            // the next pass sizes only the suffix appended since) AND apportions the exact sent view via the
            // active tokenizer, propagating each stamp onto its raw twin for the demotion loop. The sent view
            // is the pre-response request (view.compacted), never the just-appended response tail. Absent
            // usage leaves the anchor untouched (offline-estimated).
            _ <- completion.usage match
                case Present(u) =>
                    val (tokenizer, tokenizerId) = Compactor.internal.activeTokenizer(config)
                    ai.context.map(c =>
                        Compactor.internal.reanchor(c, view.compacted, u.inputTokens, tokenizer, tokenizerId)
                            .map(ai.setContext)
                    )
                case Absent => Kyo.lift(())
            calls            = messages.collect { case msg: AssistantMessage => msg.calls }.flatten
            completedCallIds = messages.collect { case ToolMessage(callId, _, _, _) => callId }
            _ <- Tool.internal.handle(ai, allTools, calls.filterNot(call => completedCallIds.contains(call.id)))
            // Extract the model's structured result directly from its result_tool call (no capturing run).
            raw = calls.filter(_.function == Completion.resultToolName).headMaybe
                .flatMap(call => Json.decode[Structure.Value](call.arguments).toMaybe)
            r <- raw match
                case Present(record) =>
                    Thought.internal.handle[A](thoughts, record, summon[Schema[A]]).map(Present(_))
                case Absent =>
                    Kyo.lift(Maybe.empty[A])
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
                extends Op[kyo.Stream[A, Async & Scope & Abort[AIStreamException]]]
            case class SetEnv(env: AIEnv)                         extends Op[AIEnv]
            case class Discard(target: AI)                        extends Op[Unit]
            case object GetState                                  extends Op[LLM.State]
            case class SetState(state: LLM.State)                 extends Op[Unit]
            case class GetSession(target: AI)                     extends Op[AISession]
            case class SetSession(target: AI, session: AISession) extends Op[Unit]
        end Op

        /** A `WeakReference` to an `AI`, used as the `State` map key so a dropped `AI` becomes reclaimable.
          * Equality and hash are by the AI's stable `id`, so a slot still matches its key after the referent is
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
