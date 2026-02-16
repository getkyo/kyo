package kyo

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.Instant as JInstant

class EasyRacerJvmTest extends Test:

    "scenario 10 - cancellable computation with load monitoring" in run {
        val sessions = new java.util.concurrent.ConcurrentHashMap[String, (Seq[Double], Duration, JInstant)]()

        val handler = HttpHandler.get("/10") { in =>
            val queryString = in.request.url
            val qIdx        = queryString.indexOf('?')
            if qIdx < 0 then
                HttpResponse(HttpResponse.Status.BadRequest): HttpResponse[?] < (Async & Any)
            else
                val paramStr = queryString.substring(qIdx + 1)
                val eqIdx    = paramStr.indexOf('=')
                if eqIdx < 0 then
                    val id = paramStr
                    Random.nextInt(5).map { extra =>
                        val duration = (5 + extra).seconds
                        Clock.now.map { now =>
                            sessions.put(id, (Seq.empty, duration, now.toJava))
                            Async.sleep(duration).andThen(HttpResponse.ok)
                        }
                    }
                else
                    val id         = paramStr.substring(0, eqIdx)
                    val loadString = paramStr.substring(eqIdx + 1)
                    val load       = loadString.toDouble
                    Clock.now.map { now =>
                        val jnow = now.toJava
                        Option(sessions.get(id)) match
                            case None =>
                                HttpResponse(HttpResponse.Status.Found): HttpResponse[?] < (Async & Any)
                            case Some((readings, duration, startTime)) =>
                                val isInBlocking = jnow.isBefore(startTime.plusSeconds(duration.toSeconds))
                                if isInBlocking then
                                    sessions.put(id, (readings :+ load, duration, startTime))
                                    HttpResponse(HttpResponse.Status.Found): HttpResponse[?] < (Async & Any)
                                else if readings.size < duration.toSeconds - 1 then
                                    HttpResponse(HttpResponse.Status.BadRequest, "Not enough readings"): HttpResponse[?] < (Async & Any)
                                else
                                    val meanLoad = readings.sum / readings.size
                                    if load > 0.3 then
                                        HttpResponse(HttpResponse.Status.Found, s"Load still high: $load"): HttpResponse[?] < (Async & Any)
                                    else if meanLoad < 0.8 then
                                        HttpResponse(
                                            HttpResponse.Status.BadRequest,
                                            s"CPU not loaded enough: $meanLoad"
                                        ): HttpResponse[?] < (Async & Any)
                                    else
                                        HttpResponse.ok("right"): HttpResponse[?] < (Async & Any)
                                    end if
                                end if
                        end match
                    }
                end if
            end if
        }
        startTestServer(handler).map { port =>
            val baseUrl       = s"http://localhost:$port/10"
            val messageDigest = MessageDigest.getInstance("SHA-512")

            def blocking(bytes: Seq[Byte]): Seq[Byte] < Async =
                Sync.defer {
                    val next = messageDigest.digest(bytes.toArray).toSeq
                    blocking(next)
                }

            def blocker(id: String): String < (Async & Abort[HttpError]) =
                Async.race(
                    HttpClient.send(HttpRequest.get(s"$baseUrl?$id")).map { resp =>
                        resp.bodyText
                    },
                    Random.nextBytes(512).map(_.toSeq).map(blocking).map(_ => "")
                )

            def reporter(id: String): String < (Async & Abort[HttpError]) =
                val osBean = ManagementFactory.getPlatformMXBean(classOf[OperatingSystemMXBean])
                val load   = osBean.getProcessCpuLoad * osBean.getAvailableProcessors
                HttpClient.send(HttpRequest.get(s"$baseUrl?$id=$load")).map { response =>
                    if response.status.isRedirect then
                        Async.delay(1.second)(reporter(id))
                    else if response.status.isSuccess then
                        response.bodyText
                    else
                        Abort.fail(HttpError.StatusError(response.status, response.bodyText))
                }
            end reporter

            Random.nextStringAlphanumeric(8).map { id =>
                Async.zip(blocker(id), reporter(id)).map { (_, result) =>
                    assert(result == "right")
                }
            }
        }
    }

end EasyRacerJvmTest
