export declare class HttpHandlerException {
  readonly error: unknown;

  static apply(error: unknown): HttpHandlerException;
};