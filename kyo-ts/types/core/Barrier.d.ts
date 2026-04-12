export declare class Barrier {
  await(): Kyo<void, Async>;
  pending(): Kyo<number, Sync>;
  readonly unsafe: Unsafe;
};