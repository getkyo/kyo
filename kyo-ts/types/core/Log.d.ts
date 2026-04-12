export declare class Log {
  debug(msg: () => Text, t: () => Throwable): Kyo<void, Sync>;
  debug(msg: () => Text): Kyo<void, Sync>;
  error(msg: () => Text): Kyo<void, Sync>;
  error(msg: () => Text, t: () => Throwable): Kyo<void, Sync>;
  info(msg: () => Text): Kyo<void, Sync>;
  info(msg: () => Text, t: () => Throwable): Kyo<void, Sync>;
  let_<A, S>(f: Kyo<A, S>): Kyo<A, S>;
  readonly level: Level;
  trace(msg: () => Text, t: () => Throwable): Kyo<void, Sync>;
  trace(msg: () => Text): Kyo<void, Sync>;
  readonly unsafe: Unsafe;
  warn(msg: () => Text, t: () => Throwable): Kyo<void, Sync>;
  warn(msg: () => Text): Kyo<void, Sync>;

  static apply(unsafe: Unsafe): Log;
  static get(): Kyo<Log, unknown>;
  readonly live: Log;
  static use<A, S>(f: (x1: Log) => Kyo<A, S>): Kyo<A, S>;
};