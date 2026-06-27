package kyo

import kyo.ai.*
import kyo.ai.Context
import kyo.ai.Context.*

class AgentTest extends kyo.test.Test[Any]:

    def serverConfig(baseUrl: String): Config =
        Config.OpenAI.default
            .apiKey("test")
            .model(Config.OpenAI, "gpt-4o", 128000)
            .apiUrl(baseUrl)

    def resultToolBody(envelopeJson: String): String =
        val escaped = Json.encode(envelopeJson)
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$escaped}}]}}]}"""

    val defaultEnvelope: String =
        """{"resultValue":"done"}"""

    "ask sends an input and awaits the typed reply" in {
        Scope.run {
            Agent.run[String] { (_: AI, in: String) =>
                in.toUpperCase
            }.map { agent =>
                agent.ask("hi").map { reply =>
                    assert(reply == "HI", s"expected 'HI', got '$reply'")
                }
            }
        }
    }

    "a closed mailbox surfaces as Abort[Closed], never a throw" in {
        Scope.run {
            Latch.init(1).map { ready =>
                Agent.run[String] { (_: AI, _: String) =>
                    ready.release.andThen(Async.never[String])
                }.map { agent =>
                    Fiber.init(Abort.run[Closed](agent.ask("trigger"))).andThen {
                        ready.await.andThen {
                            agent.close.andThen {
                                Abort.run[Closed](agent.ask("x")).map { result =>
                                    assert(result.isFailure, s"expected Abort[Closed] failure, got: $result")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "run orchestrates Prompt -> Tool -> Thought -> LLM.run in order" in {
        TestCompletionServer.run { server =>
            val config      = serverConfig(server.baseUrl)
            val instruction = "follow-this-instruction-7f3a"
            val prompt      = Prompt.init(instruction)
            val tool        = Tool.init[String]("my-tool", "a tool")(_ => "tool-result")
            val thought     = Thought.opening[String](_ => ())
            // No reasoning is applied by default, so the only opening field is the custom String thought
            // (Schema[String].structure.name = "String"); with no closing thought registered the envelope
            // carries no closingThoughts group at all.
            val customEnvelope = """{"openingThoughts":{"String":"ok"},"resultValue":"done"}"""
            server.enqueueBody(resultToolBody(customEnvelope)).andThen {
                Agent.run[String](config, prompt, tool, thought) { (self: AI, _: String) =>
                    self.gen[String]
                }.map { agent =>
                    agent.ask("go").andThen {
                        server.captured.map { caps =>
                            val body = caps.head.body
                            assert(
                                body.contains(instruction),
                                s"prompt instruction must appear in the request body; got: $body"
                            )
                            assert(
                                body.contains("my-tool"),
                                s"tool must be registered in the request body; got: $body"
                            )
                            assert(
                                body.contains("openingThoughts"),
                                s"thought schema must appear in the request body; got: $body"
                            )
                            val promptIdx  = body.indexOf(instruction)
                            val toolIdx    = body.indexOf("my-tool")
                            val thoughtIdx = body.indexOf("openingThoughts")
                            assert(
                                promptIdx < toolIdx,
                                s"prompt must be registered before tool in orchestration order; prompt=$promptIdx tool=$toolIdx"
                            )
                            assert(
                                toolIdx < thoughtIdx,
                                s"tool must be registered before thought in orchestration order; tool=$toolIdx thought=$thoughtIdx"
                            )
                        }
                    }
                }
            }
        }
    }

    "a generation inside the agent behavior extracts resultValue" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody(defaultEnvelope)).andThen {
                Agent.run[String](config) { (self: AI, _: String) =>
                    self.gen[String]
                }.map { agent =>
                    agent.ask("trigger").map { reply =>
                        assert(reply == "done", s"expected 'done' from resultValue, got '$reply'")
                    }
                }
            }
        }
    }

    "receiveLoop replies and continues per outcome" in {
        Scope.run {
            val behavior: Unit < (Agent.Context[String, String] & LLM) =
                Agent.receiveLoop[String] { (in: String) =>
                    val n = in.toIntOption.getOrElse(0)
                    if n < 3 then Loop.continue(in.toUpperCase)
                    else Loop.done
                }
            Agent.runBehavior[String](_ => behavior).map { (agent: Agent[Nothing, String, String]) =>
                Abort.run[Closed](agent.ask("1")).map { r1 =>
                    Abort.run[Closed](agent.ask("2")).map { r2 =>
                        assert(r1 == Result.succeed("1"), s"first ask should succeed with '1', got: $r1")
                        assert(r2 == Result.succeed("2"), s"second ask should succeed with '2', got: $r2")
                        agent.close.unit
                    }
                }
            }
        }
    }

    "prompt/tools/thoughts of the agent reach the generation" in {
        TestCompletionServer.run { server =>
            val config      = serverConfig(server.baseUrl)
            val instruction = "agent-system-instruction-c9b4"
            val prompt      = Prompt.init(instruction)
            server.enqueueBody(resultToolBody(defaultEnvelope)).andThen {
                Agent.run[String](config, prompt) { (self: AI, _: String) =>
                    self.gen[String]
                }.map { agent =>
                    agent.ask("input").andThen {
                        server.captured.map { caps =>
                            assert(caps.nonEmpty, "expected at least one captured request")
                            val body = caps.head.body
                            assert(
                                body.contains(instruction),
                                s"agent prompt instruction must reach the generation request; got: $body"
                            )
                        }
                    }
                }
            }
        }
    }

    "agent threads its self AI across asks" in {
        case class Question(text: String) derives Schema
        case class Answer(text: String) derives Schema, CanEqual
        TestCompletionServer.run { server =>
            val config         = serverConfig(server.baseUrl)
            val firstEnvelope  = """{"resultValue":{"text":"first"}}"""
            val secondEnvelope = """{"resultValue":{"text":"second"}}"""
            server.enqueueBody(resultToolBody(firstEnvelope)).andThen {
                server.enqueueBody(resultToolBody(secondEnvelope)).andThen {
                    Agent.run[Question](config) { (self: AI, q: Question) =>
                        self.userMessage(q.text).andThen(self.gen[Answer])
                    }.map { agent =>
                        agent.ask(Question("first-ask")).map { reply1 =>
                            assert(reply1 == Answer("first"), s"first ask should return scripted 'first', got: $reply1")
                            agent.ask(Question("second-ask")).map { reply2 =>
                                assert(reply2 == Answer("second"), s"second ask should return scripted 'second', got: $reply2")
                            }
                        }
                    }
                }
            }
        }
    }

    "a gen failure in the agent behavior does not strand the asker" in {
        // The behavior's gen fails (transport); runImpl re-throws it as a panic via getOrThrow, terminating
        // the actor. The asker must still complete (with a failure), never hang.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).retrySchedule(Schedule.done)
            server.enqueueBody("not json").andThen {
                Scope.run {
                    Agent.run[String](config) { (self: AI, _: String) =>
                        self.gen[String]
                    }.map { agent =>
                        Abort.run[Closed](agent.ask("trigger")).map { result =>
                            assert(!result.isSuccess, s"a failing gen must not strand the asker with a reply, got: $result")
                        }
                    }
                }
            }
        }
    }

    "concurrent asks are serialized through the mailbox, each getting a scripted reply" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"a"}""")).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":"b"}""")).andThen {
                    Scope.run {
                        Agent.run[String](config) { (self: AI, in: String) =>
                            self.gen[String](in)
                        }.map { agent =>
                            Async.zip(agent.ask("x"), agent.ask("y")).map { case (r1, r2) =>
                                assert(
                                    (r1 == "a" && r2 == "b") || (r1 == "b" && r2 == "a"),
                                    s"both concurrent asks should each get one scripted reply, got: ($r1, $r2)"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "receiveMax processes at most the given number of messages, then closes" in {
        Scope.run {
            val behavior: Unit < (Agent.Context[String, String] & LLM) =
                Agent.receiveMax[String](2)((in: String) => in.toUpperCase)
            Agent.runBehavior[String](_ => behavior).map { agent =>
                Abort.run[Closed](agent.ask("a")).map { r1 =>
                    Abort.run[Closed](agent.ask("b")).map { r2 =>
                        Abort.run[Closed](agent.ask("c")).map { r3 =>
                            assert(r1 == Result.succeed("A"), s"first reply: $r1")
                            assert(r2 == Result.succeed("B"), s"second reply: $r2")
                            assert(r3.isFailure, s"after receiveMax(2) the mailbox is closed, got: $r3")
                        }
                    }
                }
            }
        }
    }

    "stateful receiveLoop threads state across messages" in {
        Scope.run {
            val behavior: Int < (Agent.Context[String, Int] & LLM) =
                Agent.receiveLoop[String](0) { (sum: Int, in: String) =>
                    val next = sum + in.toIntOption.getOrElse(0)
                    Loop.continue(next, next)
                }
            Agent.runBehavior[String](_ => behavior).map { agent =>
                Abort.run[Closed](agent.ask("2")).map { r1 =>
                    Abort.run[Closed](agent.ask("3")).map { r2 =>
                        assert(r1 == Result.succeed(2), s"running sum after 2: $r1")
                        assert(r2 == Result.succeed(5), s"running sum after 2 then 3: $r2")
                        agent.close.unit
                    }
                }
            }
        }
    }

    "an agent's conversation survives across asks (cross-ask persistence)" in {
        // The stable AI instance's conversation history survives the actor park-and-resume cycle: the second
        // ask's captured request must contain the first ask's user message. The example ascribes to its row.
        case class Question(text: String) derives Schema
        case class Answer(text: String) derives Schema, CanEqual
        TestCompletionServer.run { server =>
            val config      = serverConfig(server.baseUrl)
            val firstReply  = resultToolBody("""{"resultValue":{"text":"first-answer"}}""")
            val secondReply = resultToolBody("""{"resultValue":{"text":"second-answer"}}""")
            server.enqueueBody(firstReply).andThen {
                server.enqueueBody(secondReply).andThen {
                    val example: Answer < (Scope & Async & Abort[Closed]) =
                        Agent.run[Question](config) { (self: AI, q: Question) =>
                            self.userMessage(q.text).andThen(self.gen[Answer])
                        }.map { agent =>
                            agent.ask(Question("first-turn-message")).map { _ =>
                                agent.ask(Question("second-turn-message"))
                            }
                        }
                    example.map { result =>
                        assert(result == Answer("second-answer"), s"second ask must return the scripted answer, got: $result")
                        server.captured.map { caps =>
                            assert(caps.size == 2, s"expected 2 captured requests, got: ${caps.size}")
                            assert(
                                caps(1).body.contains("first-turn-message"),
                                s"second ask's request must contain the first ask's user message (cross-ask persistence via parked continuation); got: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "an aborting agent behavior surfaces as a typed failure without stranding the asker" in {
        // When the agent behavior aborts immediately, the asker must NOT be stranded.
        // Actor.ask's awaitReply + onTerminated ensures the reply promise completes with the actor's
        // terminal outcome (Closed or typed Error), as a typed failure, never a panic or a hang.
        Scope.run {
            Agent.run[String] { (_: AI, _: String) =>
                Abort.fail[String]("behavior-failure"): String < Abort[String]
            }.map { agent =>
                Abort.run[Closed | String](agent.ask("trigger")).map { result =>
                    assert(
                        !result.isPanic,
                        s"an aborting behavior must complete the ask (not strand or panic); got: $result"
                    )
                    assert(
                        result.isFailure,
                        s"an aborting behavior's ask must surface as a typed failure (Closed or Error); got: $result"
                    )
                }
            }
        }
    }

end AgentTest
