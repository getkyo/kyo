export declare class CounterGauge {
  collect(): Kyo<number, Sync>;
  readonly unsafe: UnsafeCounterGauge;
};