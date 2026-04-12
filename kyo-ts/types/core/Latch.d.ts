export declare class Latch {
  await(): Kyo<void, Async>;
  pending(): Kyo<number, Sync>;
  release(): Kyo<void, Sync>;
  readonly unsafe: Unsafe;
};