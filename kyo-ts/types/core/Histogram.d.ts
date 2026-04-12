export declare class Histogram {
  count(): Kyo<number, Sync>;
  observe(v: number): Kyo<void, Sync>;
  readonly unsafe: UnsafeHistogram;
  valueAtPercentile(v: number): Kyo<number, Sync>;
};