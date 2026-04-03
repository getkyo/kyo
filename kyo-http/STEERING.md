# STEERING

Stop analyzing. Start implementing. The Native TLS test setup:

1. Create TlsTestHelper.scala that generates cert+key with openssl CLI
2. Update HttpTestPlatformBackend to set tlsServerAvailable = true
3. The test's `runServer` passes `config.tls(TlsConfig.default)` — but TlsConfig.default has no cert paths. Create a TlsTestHelper that provides a TlsConfig with cert/key paths for the server.
4. For client-side trust: TlsConfig has `trustAll = true`. Check if Native TLS supports this — if not, skip the TLS client validation for now.

For JS: skip for now. Focus on Native first.

Compile, don't run tests yet.
