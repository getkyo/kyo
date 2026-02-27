package demo

import kyo.*

/** API Gateway that aggregates weather (Open-Meteo) and currency (Frankfurter) data.
  *
  * Demonstrates: typed routes with path/query params, parallel external API calls, client config (baseUrl, timeout), server filters,
  * OpenAPI generation, error handling.
  *
  * Endpoints: GET /weather/:city - current weather GET /rates?base=USD&to=EUR - exchange rates GET /travel/:city?budget=1000&currency=USD -
  * travel info (weather + rates combined)
  */
object ApiGateway extends KyoApp:

    // --- External API models ---

    // Open-Meteo
    case class MeteoResponse(current: MeteoCurrentData) derives Schema
    case class MeteoCurrentData(temperature_2m: Double, wind_speed_10m: Double) derives Schema

    // Frankfurter
    case class FrankfurterResponse(base: String, date: String, rates: Map[String, Double]) derives Schema

    // --- Our API models ---
    case class WeatherInfo(city: String, temperature: Double, windSpeed: Double, unit: String) derives Schema
    case class RateInfo(base: String, date: String, rates: Map[String, Double]) derives Schema
    case class TravelInfo(weather: WeatherInfo, localCurrency: String, exchangeRate: Double, budgetInLocal: Double) derives Schema
    case class ApiError(error: String, detail: String) derives Schema

    // --- City coordinates ---
    val cities: Map[String, (Double, Double)] = Map(
        "london"    -> (51.51, -0.13),
        "tokyo"     -> (35.68, 139.69),
        "new-york"  -> (40.71, -74.01),
        "paris"     -> (48.86, 2.35),
        "berlin"    -> (52.52, 13.41),
        "sao-paulo" -> (-23.55, -46.63),
        "sydney"    -> (-33.87, 151.21),
        "mumbai"    -> (19.08, 72.88)
    )

    val cityCurrency: Map[String, String] = Map(
        "london"    -> "GBP",
        "tokyo"     -> "JPY",
        "new-york"  -> "USD",
        "paris"     -> "EUR",
        "berlin"    -> "EUR",
        "sao-paulo" -> "BRL",
        "sydney"    -> "AUD",
        "mumbai"    -> "INR"
    )

    // --- External API calls ---

    def fetchWeather(city: String): WeatherInfo < (Async & Abort[HttpError]) =
        cities.get(city.toLowerCase) match
            case None => Abort.fail(HttpError.ConnectionError(s"Unknown city: $city", new RuntimeException(s"Unknown city: $city")))
            case Some((lat, lon)) =>
                HttpClient.withConfig(_.timeout(5.seconds)) {
                    val url = s"https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,wind_speed_10m"
                    HttpClient.getJson[MeteoResponse](url).map { r =>
                        WeatherInfo(city, r.current.temperature_2m, r.current.wind_speed_10m, "celsius")
                    }
                }

    def fetchRates(base: String, symbols: String): RateInfo < (Async & Abort[HttpError]) =
        HttpClient.withConfig(_.timeout(5.seconds)) {
            val url = s"https://api.frankfurter.dev/v1/latest?base=$base&symbols=$symbols"
            HttpClient.getJson[FrankfurterResponse](url).map { r =>
                RateInfo(r.base, r.date, r.rates)
            }
        }

    // --- Route handlers ---

    val loggingFilter = HttpFilter.server.logging
        .andThen(HttpFilter.server.cors())

    val weatherRoute = HttpRoute
        .getRaw("weather" / HttpPath.Capture[String]("city"))
        .filter(loggingFilter)
        .response(_.bodyJson[WeatherInfo].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Current weather for a city").tag("weather"))
        .handler { req =>
            if !cities.contains(req.fields.city.toLowerCase) then
                Abort.fail(ApiError("Unknown city", s"City '${req.fields.city}' not found. Try: ${cities.keys.mkString(", ")}"))
            else
                fetchWeather(req.fields.city).map(HttpResponse.okJson(_))
        }

    val ratesRoute = HttpRoute
        .getRaw("rates")
        .filter(loggingFilter)
        .request(
            _.query[String]("base", default = Present("USD"))
                .query[String]("to", default = Present("EUR,GBP,JPY"))
        )
        .response(_.bodyJson[RateInfo].error[ApiError](HttpStatus.BadRequest))
        .metadata(_.summary("Exchange rates").tag("finance"))
        .handler { req =>
            fetchRates(req.fields.base, req.fields.to).map(HttpResponse.okJson(_))
        }

    val travelRoute = HttpRoute
        .getRaw("travel" / HttpPath.Capture[String]("city"))
        .filter(loggingFilter)
        .request(
            _.query[Int]("budget", default = Present(1000))
                .query[String]("currency", default = Present("USD"))
        )
        .response(_.bodyJson[TravelInfo].error[ApiError](HttpStatus.NotFound))
        .metadata(_.summary("Travel info: weather + currency conversion").tag("travel"))
        .handler { req =>
            val in = req.fields
            for
                localCurrency <- cityCurrency.get(in.city.toLowerCase) match
                    case Some(c) => c: String < Any
                    case None    => Abort.fail(ApiError("not_found", s"Unknown city: ${in.city}. Try: ${cities.keys.mkString(", ")}"))
                result <- Async.zip(
                    fetchWeather(in.city),
                    fetchRates(in.currency, localCurrency)
                )
                (weather, rates) = result
                rate             = rates.rates.getOrElse(localCurrency, 1.0)
                budgetInLocal    = in.budget * rate
            yield HttpResponse.okJson(TravelInfo(weather, localCurrency, rate, budgetInLocal))
            end for
        }

    val health = HttpHandler.health()

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        HttpServer.init(
            HttpServer.Config().port(port).openApi("/openapi.json", "Travel API Gateway")
        )(weatherRoute, ratesRoute, travelRoute, health).map { server =>
            for
                _ <- Console.printLine(s"API Gateway running on http://localhost:${server.port}")
                _ <- Console.printLine(s"  GET /weather/tokyo")
                _ <- Console.printLine(s"  GET /rates?base=USD&to=EUR,GBP")
                _ <- Console.printLine(s"  GET /travel/paris?budget=2000&currency=USD")
                _ <- Console.printLine(s"  GET /openapi.json")
                _ <- server.await
            yield ()
        }
    }
end ApiGateway
