package kyo.internal

import kyo.SqlConfig

/** The network view of an address a test built or parsed from a network URL.
  *
  * `SqlConfig.Address` is sealed over a network endpoint and a local one, so a host and a port are reachable only after narrowing. Every
  * test here parses or builds a network address outright, so the narrowing restates what the test already set up.
  *
  * Throws rather than failing through `AssertScope`, so it can be used in a suite's setup as well as inside a leaf. The throw is a
  * statement about the TEST being wrong, not about the code under test: a suite reaching it built one kind of address and read the other.
  */
extension (address: SqlConfig.Address)
    def network: SqlConfig.Address.Network =
        address match
            case n: SqlConfig.Address.Network => n
            case other =>
                throw new AssertionError(
                    s"this test reads a host and a port, so it needs a network address, and it was given $other"
                )
end extension
