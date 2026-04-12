export declare class Counter {
  add(v: number): Kyo<void, Sync>;
  get(): Kyo<number, Sync>;
  inc(): Kyo<void, Sync>;
  readonly unsafe: UnsafeCounter;
};