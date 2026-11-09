/**
 * API 클라이언트 (fetch)
 *
 * - 모든 요청은 상대 경로(/api/...)로 보낸다 : 개발은 Vite 프록시, 운영은 같은 출처(Spring Boot)
 * - 인증은 브라우저가 세션 쿠키를 자동으로 보낸다. 세션 ID 는 읽거나 저장하지 않는다.
 * - 변경 요청(POST/PUT/PATCH/DELETE)에는 GET /api/auth/csrf 로 받은 토큰을 헤더에 넣는다. (메모리에만 보관)
 * - 오류는 기본으로 공통 알림을 띄운 뒤 ApiError 로 reject 한다. (silent: true 면 알림 없이 reject)
 */

export interface ErrorBody {
  status?: number;
  code?: string;
  message?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly body?: unknown;
  /** 공통 알림을 이미 띄웠는지 (호출한 쪽에서 다시 알릴 필요 없음) */
  handled = false;

  constructor(status: number, message: string, code?: string, body?: unknown) {
    super(message);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

interface CsrfToken {
  headerName: string;
  token: string;
}

let csrfToken: CsrfToken | null = null;
let csrfPromise: Promise<CsrfToken> | null = null;

/** 로그인/로그아웃 후에는 서버 토큰이 바뀌므로 버리고 다시 받는다 */
export function resetCsrf() {
  csrfToken = null;
  csrfPromise = null;
}

async function getCsrf(): Promise<CsrfToken> {
  if (csrfToken) return csrfToken;
  if (!csrfPromise) {
    csrfPromise = fetch('/api/auth/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(async (res) => {
        if (!res.ok) throw new ApiError(res.status, '보안 토큰을 받지 못했습니다.');
        const body = (await res.json()) as CsrfToken;
        csrfToken = body;
        return body;
      })
      .finally(() => {
        csrfPromise = null;
      });
  }
  return csrfPromise;
}

type ErrorListener = (error: ApiError) => void;
let errorListener: ErrorListener | null = null;

/** 공통 오류 처리 (알림 / 401 시 로그인 화면 이동) : App 에서 한 번 등록한다 */
export function setApiErrorListener(listener: ErrorListener | null) {
  errorListener = listener;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** JSON 으로 보낼 값 (FormData / URLSearchParams 는 그대로 보낸다) */
  body?: unknown;
  /** 오류 시 공통 알림을 띄우지 않는다 */
  silent?: boolean;
  signal?: AbortSignal;
}

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

async function send(url: string, options: RequestOptions, retried = false): Promise<Response> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Requested-With': 'XMLHttpRequest',
  };

  let body: BodyInit | undefined;
  if (options.body instanceof FormData || options.body instanceof URLSearchParams) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }

  if (MUTATING.has(method)) {
    const csrf = await getCsrf();
    headers[csrf.headerName] = csrf.token;
  }

  let res: Response;
  try {
    res = await fetch(url, { method, headers, body, credentials: 'same-origin', signal: options.signal });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    // 서버 응답을 받지 못한 경우 : 세션 만료로 보지 않는다
    throw new ApiError(0, '서버에 연결할 수 없습니다. 네트워크 상태를 확인한 뒤 다시 시도해주세요.', 'NETWORK_ERROR');
  }

  // 토큰이 바뀐 경우(세션 만료 후 새 세션 등) 한 번만 새 토큰으로 다시 보낸다
  if (res.status === 403 && MUTATING.has(method) && !retried) {
    const err = await peekError(res);
    if (err.code === 'CSRF_INVALID') {
      resetCsrf();
      return send(url, options, true);
    }
  }
  return res;
}

async function peekError(res: Response): Promise<ErrorBody> {
  try {
    return (await res.clone().json()) as ErrorBody;
  } catch {
    return {};
  }
}

async function toError(res: Response): Promise<ApiError> {
  const body = await peekError(res);
  let message = body.message;
  if (res.status === 401) message = message ?? '로그인이 필요합니다.';
  else if (res.status === 403 || res.status === 405) {
    message = body.code === 'CSRF_INVALID' ? body.message : '해당 작업을 수행할 접근 권한이 없습니다.';
  }
  return new ApiError(res.status, message ?? '알 수 없는 오류가 발생했습니다.', body.code, body);
}

function fail(error: unknown, options: RequestOptions): never {
  if (error instanceof ApiError && !options.silent && errorListener) {
    error.handled = true;
    errorListener(error);
  }
  throw error;
}

/** JSON 응답 API */
export async function api<T = unknown>(url: string, options: RequestOptions = {}): Promise<T> {
  let res: Response;
  try {
    res = await send(url, options);
  } catch (e) {
    fail(e, options);
  }
  if (!res.ok) fail(await toError(res), options);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** 파일 다운로드 API (blob) */
export async function download(url: string, fileName: string, options: RequestOptions = {}): Promise<void> {
  let res: Response;
  try {
    res = await send(url, options);
  } catch (e) {
    fail(e, options);
  }
  if (!res.ok) fail(await toError(res), options);

  const blob = await res.blob();
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(href); // 메모리 해제
}

/** 경로 조각 인코딩 */
export const enc = (value: unknown) => encodeURIComponent(String(value ?? ''));
