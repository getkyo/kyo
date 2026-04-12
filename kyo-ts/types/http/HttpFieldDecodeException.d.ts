export declare class HttpFieldDecodeException {
  readonly cause: Text | Throwable;
  readonly detail: string;
  readonly fieldName: string;
  readonly fieldType: string;
  readonly method: string;
  readonly url: string;
};