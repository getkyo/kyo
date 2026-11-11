package kyo.net

import kyo.*

class NetAddressTest extends Test:

    import AllowUnsafe.embrace.danger

    private def bytes(host: String)(using Frame): Chunk[Int] < (Async & Abort[NetDnsResolutionException]) =
        NetAddress.resolve(host).safe.get.map(address => Chunk.from(address.toArray).map(_ & 0xff))

    "an IPv4 literal is its four bytes" in {
        bytes("93.184.215.14").map(address => assert(address == Chunk(93, 184, 215, 14)))
    }

    "an IPv6 literal is its sixteen bytes" in {
        bytes("2001:db8::1").map(address => assert(address == Chunk(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    }

    "localhost is the IPv4 loopback, whatever its case" in {
        for
            lower <- bytes("localhost")
            upper <- bytes("LocalHost")
        yield assert(lower == Chunk(127, 0, 0, 1) && upper == Chunk(127, 0, 0, 1))
    }

    "ip6-localhost and ip6-loopback are the IPv6 loopback" in {
        val loopback = Chunk.fill(15)(0).append(1)
        for
            localhost <- bytes("ip6-localhost")
            named     <- bytes("ip6-loopback")
        yield assert(localhost == loopback && named == loopback)
        end for
    }

    "a name no resolver answers fails with the name" in {
        Abort.run(bytes("nonexistent.invalid")).map {
            case Result.Failure(failure) => assert(failure.host == "nonexistent.invalid")
            case other                   => fail(s"expected a NetDnsResolutionException, got $other")
        }
    }

end NetAddressTest
