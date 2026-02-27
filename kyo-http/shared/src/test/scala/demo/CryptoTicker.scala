package demo

import kyo.*

/** Cryptocurrency price ticker with NDJSON streaming.
  *
  * Server polls CoinGecko's free API for live prices and serves them as an NDJSON stream. Companion client consumes the stream using
  * HttpClient.getNdJson.
  *
  * Demonstrates: NDJSON server streaming, HttpClient.getNdJson (client-side NDJSON consumption), custom retryOn predicate,
  * HttpClient.withConfig with connectTimeout, OpenAPI.
  */
object CryptoTicker extends KyoApp:

    // CoinGecko response: Map of coin id -> Map of currency -> price
    // e.g. {"bitcoin":{"usd":67000},"ethereum":{"usd":3500}}
    case class PriceData(bitcoin: CurrencyPrice, ethereum: CurrencyPrice, solana: CurrencyPrice) derives Schema
    case class CurrencyPrice(usd: Double, eur: Double, gbp: Double) derives Schema

    case class Tick(coin: String, usd: Double, eur: Double, gbp: Double, timestamp: String) derives Schema

    def fetchPrices: Seq[Tick] < (Async & Abort[HttpError]) =
        HttpClient.withConfig(
            _.timeout(10.seconds)
                .connectTimeout(5.seconds)
                .retryOn(status => status.isServerError || status.code == 429)
                .retry(Schedule.exponentialBackoff(500.millis, 2.0, 10.seconds).repeat(3))
        ) {
            for
                data <- HttpClient.getJson[PriceData](
                    "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,solana&vs_currencies=usd,eur,gbp"
                )
                now <- Clock.now
                ts = now.toString
            yield Seq(
                Tick("bitcoin", data.bitcoin.usd, data.bitcoin.eur, data.bitcoin.gbp, ts),
                Tick("ethereum", data.ethereum.usd, data.ethereum.eur, data.ethereum.gbp, ts),
                Tick("solana", data.solana.usd, data.solana.eur, data.solana.gbp, ts)
            )
        }

    // GET /prices — latest snapshot
    val pricesRoute = HttpHandler.getJson[Seq[Tick]]("prices") { _ =>
        Abort.run[HttpError](fetchPrices).map(_.getOrElse(Seq.empty))
    }

    // GET /stream — NDJSON stream of price ticks, polls every 15s
    val streamHandler = HttpHandler.getNdJson[Tick]("stream") { _ =>
        Stream[Tick, Async] {
            Loop.foreach {
                for
                    _      <- Async.delay(15.seconds)(())
                    result <- Abort.run[HttpError](fetchPrices)
                    ticks = result.getOrElse(Seq.empty)
                yield Emit.valueWith(Chunk.from(ticks))(Loop.continue)
            }
        }
    }

    val health = HttpHandler.health()

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        HttpServer.init(
            HttpServer.Config().port(port).openApi("/openapi.json", "Crypto Ticker")
        )(pricesRoute, streamHandler, health).map { server =>
            for
                _ <- Console.printLine(s"CryptoTicker running on http://localhost:${server.port}")
                _ <- Console.printLine(s"  curl http://localhost:${server.port}/prices")
                _ <- Console.printLine(s"  curl -N http://localhost:${server.port}/stream")
                _ <- server.await
            yield ()
        }
    }
end CryptoTicker

/** Client that consumes the CryptoTicker NDJSON stream.
  *
  * Demonstrates: HttpClient.getNdJson (client-side NDJSON consumption), stream processing with take + foreachChunk.
  *
  * Requires CryptoTicker server running on port 3013.
  */
object CryptoTickerClient extends KyoApp:

    import CryptoTicker.Tick

    run {
        for
            _ <- Console.printLine("=== CryptoTicker Client ===")
            _ <- Console.printLine("Connecting to NDJSON stream at http://localhost:3013/stream...")

            // Consume NDJSON stream using client-side convenience method
            stream <- HttpClient.withConfig(
                _.timeout(120.seconds)
                    .connectTimeout(5.seconds)
            ) {
                HttpClient.getNdJson[Tick]("http://localhost:3013/stream")
            }

            // Process first 9 ticks (3 rounds of 3 coins)
            _ <- stream.take(9).foreachChunk { chunk =>
                Kyo.foreach(chunk.toSeq) { tick =>
                    Console.printLine(
                        s"  ${tick.coin}: $$${tick.usd} USD / €${tick.eur} EUR / £${tick.gbp} GBP  [${tick.timestamp}]"
                    )
                }
            }
            _ <- Console.printLine("Done — received 9 ticks.")
        yield ()
    }
end CryptoTickerClient
