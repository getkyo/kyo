package kyo

/** No client runners on jvm/native: `object Three` mixes in this empty trait there. `runMount`,
  * `loadGltf`, `toImage`, `testDriver`, `texture`, and `custom` all bind live WebGL and exist only on
  * js/wasm. On the server the surface is `Three.embed` plus the pure scene factories: the server
  * constructs, SSRs, and drives the scene, and the client island renders it.
  */
private[kyo] trait ThreeRunnerOps:
    /** No-op on jvm/native: there is no WebGL backend to register (the server constructs and drives the
      * scene, the client island owns the mount). Mirrors the js/wasm carrier so shared `Three.embed`'s
      * registration call resolves on every platform.
      */
    private[kyo] def ensureBackendRegistered(): Unit = ()
end ThreeRunnerOps
