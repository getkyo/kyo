export declare class KyoException {
  readonly frame: never;
  getCause(): Throwable;
  getMessage(): string;

  readonly maxMessageLength: number;
};