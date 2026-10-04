package kyo.internal.teams

import kyo.*

class ServiceUrlsTest extends kyo.test.Test[Any]:

    private def url(text: String)(using Frame): HttpUrl = HttpUrl.parse(text).getOrThrow

    "a Teams id joined as a segment travels through a URL and back unchanged" in {
        val id     = "19:efa9296d959346209fea44151c742e73@thread.skype"
        val joined = ServiceUrls.join(url("https://smba.trafficmanager.net/amer/"), Chunk("v3", "conversations", id, "activities"))
        assert(joined.full ==
            "https://smba.trafficmanager.net/amer/v3/conversations/19%3Aefa9296d959346209fea44151c742e73%40thread.skype/activities")
        val reparsed = HttpUrl.parse(joined.full).getOrThrow
        val segment  = reparsed.path.split('/')(4)
        assert(java.net.URLDecoder.decode(segment, "UTF-8") == id)
    }

    "every byte outside the unreserved characters is percent-encoded, so a segment cannot add a segment, a query or a fragment" in {
        assert(ServiceUrls.encode("a/b?c#d e%f;g") == "a%2Fb%3Fc%23d%20e%25f%3Bg")
        assert(ServiceUrls.encode("AZaz09-._~") == "AZaz09-._~")
        assert(ServiceUrls.encode("é") == "%C3%A9")
    }

    "a base without a trailing slash gets one before the segments" in {
        assert(ServiceUrls.join(url("https://smba.infra.gcc.teams.microsoft.com/teams"), Chunk("v3", "conversations")).full ==
            "https://smba.infra.gcc.teams.microsoft.com/teams/v3/conversations")
        assert(ServiceUrls.join(url("http://127.0.0.1:3978"), Chunk("v3")).full == "http://127.0.0.1:3978/v3")
    }

    "an origin is allowed when scheme, host and port match, scheme and host in any ASCII case" in {
        val origins = Chunk(url("https://smba.trafficmanager.net"), url("http://127.0.0.1:3978"))
        assert(Chunk(
            "https://smba.trafficmanager.net/amer/",
            "HTTPS://SMBA.TrafficManager.NET/teams/",
            "https://smba.trafficmanager.net:443/emea/",
            "http://smba.trafficmanager.net/amer/",
            "https://smba.trafficmanager.net:8443/amer/",
            "https://evil.trafficmanager.net/amer/",
            "https://smba.trafficmanager.net.evil.com/amer/",
            "http://127.0.0.1:3978/",
            "http://127.0.0.1:3979/"
        ).map(t => ServiceUrls.allowed(origins, url(t))) == Chunk(true, true, true, false, false, false, false, true, false))
    }

    "a config's origin is a base URL whose path is /" in {
        assert(ServiceUrls.originProblemOf(url("https://smba.trafficmanager.net")) == Absent)
        assert(ServiceUrls.originProblemOf(url("https://smba.trafficmanager.net/teams/")) == Present(TeamsException.UrlProblem.Path))
    }

end ServiceUrlsTest
