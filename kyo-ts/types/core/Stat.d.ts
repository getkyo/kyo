export declare class Stat {
  initCounter(name: string, description: string): Counter;
  initCounterGauge(name: string, description: string, f: () => number): CounterGauge;
  initGauge(name: string, description: string, f: () => number): Gauge;
  initHistogram(name: string, description: string): Histogram;
  scope(path: string[]): Stat;
  traceSpan<A, S>(name: string, attributes: Attributes, v: () => Kyo<A, S>): Kyo<A, Sync & S>;

  static traceListen<A, S>(receiver: TraceReceiver, v: Kyo<A, S>): Kyo<A, Sync & S>;
};