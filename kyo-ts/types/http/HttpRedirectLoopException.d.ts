export declare class HttpRedirectLoopException {
  readonly chain: Chunk<string>;
  readonly count: number;
  readonly method: string;
  readonly url: string;
};