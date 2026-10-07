package kyo

/** `WhatsAppConfig.retry` against a local Graph that answers each attempt from a script.
  *
  * A retry's wait runs on virtual time. The test advances it one second per step and records the step at which the server sees each
  * attempt after the first; a wait of `d` registers at virtual time 0 or later, so its attempt cannot arrive before step `d`. The request
  * timeout is an hour, so no step reaches it.
  */
class WhatsAppRetryTest extends BaseWhatsAppTest:

    val phoneId = WhatsAppId.PhoneNumberId("106540352242922")
    val to      = WhatsAppId.WaId("16505551234")

    final case class Reply(status: HttpStatus, body: String, retryAfter: Maybe[String] = Absent)

    val sent = Reply(HttpStatus.OK, """{"messaging_product":"whatsapp","contacts":[{"wa_id":"W"}],"messages":[{"id":"wamid.X"}]}""")

    def throttled(retryAfter: Maybe[String] = Absent): Reply =
        Reply(
            HttpStatus.TooManyRequests,
            """{"error":{"code":130429,"type":"OAuthException","message":"Rate limit hit","fbtrace_id":"fb123"}}""",
            retryAfter
        )

    val windowClosed = Reply(
        HttpStatus.BadRequest,
        """{"error":{"code":131047,"type":"OAuthException","message":"Re-engagement message","fbtrace_id":"fb1"}}"""
    )

    def throughputLimit(retryAfter: Maybe[Duration])(using Frame): WhatsAppException =
        WhatsAppThroughputRateLimitException("send", Absent, "Rate limit hit", Absent, Present("fb123"), retryAfter)

    /** A Graph that answers the n-th send with the n-th reply, the last one repeated, and reports each attempt's number. */
    def withGraph[A, S](replies: Reply*)(test: (Int, Channel[Int]) => A < S)(using
        Frame
    ): A < (S & Async & Scope & Abort[HttpBindException | HttpRouteException | Closed]) =
        AtomicInt.init.map { served =>
            Channel.init[Int](64).map { attempts =>
                val handler = HttpRoute.postRaw("v25.0" / phoneId.value / "messages")
                    .request(_.bodyBinary)
                    .response(_.bodyText)
                    .handler { _ =>
                        served.incrementAndGet.map { n =>
                            val reply    = replies(math.min(n, replies.size) - 1)
                            val response = HttpResponse(reply.status).addField("body", reply.body)
                            attempts.put(n).andThen(reply.retryAfter.fold(response)(response.setHeader("Retry-After", _)))
                        }
                    }
                HttpServer.init(0, "localhost")(handler).map(server => test(server.port, attempts))
            }
        }

    def configAt(port: Int, retry: Maybe[Schedule], retryMaxDelay: Duration = 60.seconds)(using Frame): WhatsAppConfig =
        WhatsAppConfig.init(
            tokenOf("TEST_TOKEN"),
            phoneId,
            baseUrl = url(s"http://localhost:$port"),
            requestTimeout = 1.hour,
            retry = retry,
            retryMaxDelay = retryMaxDelay
        ).getOrThrow

    def send(config: WhatsAppConfig)(using Frame): Result[WhatsAppException, WhatsAppSendResult] < (Async & Scope) =
        Abort.run[WhatsAppException](WhatsApp.run(config)(WhatsApp.send(to, WhatsAppMessage.Text("hi"))))

    /** Advances virtual time a second at a time until the server has seen `count` attempts, the first already taken, and answers the step
      * at which each later attempt arrived. Stops at 600 steps, so a retry that never comes fails the leaf instead of hanging it.
      */
    def stepsUntil(control: Clock.TimeControl, attempts: Channel[Int], count: Int)(using
        Frame
    ): Chunk[Int] < (Async & Abort[Closed]) =
        Loop(1, 0, Chunk.empty[Int]) { (seen, step, arrivals) =>
            if seen == count || step >= 600 then Loop.done(arrivals)
            else
                attempts.poll.map {
                    case Present(n) => Loop.continue(n, step, arrivals :+ step)
                    case Absent     => control.advance(1.second).andThen(Loop.continue(seen, step + 1, arrivals))
                }
        }

    "a retryable code is sent again after the schedule's delay, and the next answer is the result" in {
        withGraph(throttled(), sent) { (port, attempts) =>
            Clock.withTimeControl { control =>
                Fiber.init(send(configAt(port, Present(Schedule.fixed(5.seconds))))).map { fiber =>
                    attempts.take.andThen(stepsUntil(control, attempts, 2)).map { arrivals =>
                        fiber.get.map { result =>
                            assert(result.map(_.messageId) == Result.succeed(WhatsAppId.MessageId("wamid.X")))
                            assert(arrivals.size == 1 && arrivals(0) >= 5, s"second attempt at virtual seconds $arrivals")
                        }
                    }
                }
            }
        }
    }

    "an answer's Retry-After longer than the schedule's delay is the wait" in {
        withGraph(throttled(Present("30")), sent) { (port, attempts) =>
            Clock.withTimeControl { control =>
                Fiber.init(send(configAt(port, Present(Schedule.fixed(1.second))))).map { fiber =>
                    attempts.take.andThen(stepsUntil(control, attempts, 2)).map { arrivals =>
                        fiber.get.map { result =>
                            assert(result.isSuccess, result.toString)
                            assert(arrivals.size == 1 && arrivals(0) >= 30, s"second attempt at virtual seconds $arrivals")
                        }
                    }
                }
            }
        }
    }

    "an exhausted schedule answers the last attempt's failure" in {
        withGraph(throttled()) { (port, attempts) =>
            Clock.withTimeControl { control =>
                Fiber.init(send(configAt(port, Present(Schedule.fixed(1.second).take(2))))).map { fiber =>
                    attempts.take.andThen(stepsUntil(control, attempts, 3)).map { arrivals =>
                        fiber.get.map { result =>
                            attempts.poll.map { more =>
                                assert(result == Result.fail(throughputLimit(Absent)))
                                assert(arrivals.size == 2, s"later attempts at virtual seconds $arrivals")
                                assert(more == Absent)
                            }
                        }
                    }
                }
            }
        }
    }

    "a code Meta does not call retryable is the answer of the one attempt" in {
        withGraph(windowClosed, sent) { (port, attempts) =>
            send(configAt(port, Present(Schedule.fixed(1.second)))).map { result =>
                attempts.take.map { first =>
                    attempts.poll.map { more =>
                        assert(result == Result.fail(
                            WhatsAppWindowClosedException("send", Absent, "Re-engagement message", Absent, Present("fb1"))
                        ))
                        assert((first, more) == (1, Absent))
                    }
                }
            }
        }
    }

    "without a schedule a retryable code is the answer, carrying its Retry-After" in {
        withGraph(throttled(Present("7")), sent) { (port, attempts) =>
            send(configAt(port, Absent)).map { result =>
                attempts.take.map { first =>
                    attempts.poll.map { more =>
                        assert(result == Result.fail(throughputLimit(Present(7.seconds))))
                        assert((first, more) == (1, Absent))
                    }
                }
            }
        }
    }

    "a Retry-After past retryMaxDelay is not waited for: the answer carries it" in {
        withGraph(throttled(Present("120")), sent) { (port, attempts) =>
            send(configAt(port, Present(Schedule.fixed(1.second)), retryMaxDelay = 60.seconds)).map { result =>
                attempts.take.map { first =>
                    attempts.poll.map { more =>
                        assert(result == Result.fail(throughputLimit(Present(120.seconds))))
                        assert((first, more) == (1, Absent))
                    }
                }
            }
        }
    }

end WhatsAppRetryTest
