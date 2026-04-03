# STEERING

The test framework (BaseKyoCoreTest.run) already has a default 15-second timeout. 
Remove ALL `Async.timeout` guards from tests — they are redundant.
The only `Async.timeout` that should remain is in tests that TEST the timeout feature itself (e.g., HttpClientTest timeout configuration tests).
