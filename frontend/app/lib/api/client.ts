// frontend/app/lib/api/client.ts


function getApiBaseUrl() {

  if (typeof window !== 'undefined') {
    return '';
  }

  return process.env.INTERNAL_API_BASE_URL || 'http://tomcat:8080';
}

const API_BASE_URL = getApiBaseUrl();
const DEFAULT_TIMEOUT = 300000; // 5 minutes in milliseconds
const GET_CACHE_TTL_MS = 60_000;
const MAX_GET_CACHE_ENTRIES = 200;
const LEGACY_GITHUB_TOKEN_STORAGE_KEY = 'signals.githubToken';

if (typeof window !== 'undefined') {
  try {
    window.sessionStorage.removeItem(LEGACY_GITHUB_TOKEN_STORAGE_KEY);
  } catch {
    // Storage may be disabled by the browser.
  }
}

type CacheEntry = {
  expiresAt: number;
  data: unknown;
};

const getCache = new Map<string, CacheEntry>();
const inFlightGets = new Map<string, Promise<unknown>>();

function isSessionCachedGet(path: string) {
  return path.includes('/api/gitlab/projects/')
    || path.includes('/api/projects/')
    || path.startsWith('/api/project-satisfaction-surveys')
    || path === '/api/groups'
    || path === '/api/ai/comparison-analysis'
    || path === '/api/ai/evaluate';
}

function getCacheExpiration(path: string) {
  return isSessionCachedGet(path)
    ? Number.POSITIVE_INFINITY
    : Date.now() + GET_CACHE_TTL_MS;
}

function shouldCacheGet(path: string) {
  return !path.includes('/api/ai/analysis-progress');
}

function setGetCache(url: string, path: string, data: unknown) {
  for (const [key, entry] of getCache) {
    if (entry.expiresAt <= Date.now()) {
      getCache.delete(key);
    }
  }
  if (getCache.size >= MAX_GET_CACHE_ENTRIES) {
    const oldestKey = getCache.keys().next().value;
    if (oldestKey) {
      getCache.delete(oldestKey);
    }
  }
  getCache.set(url, {
    expiresAt: getCacheExpiration(path),
    data,
  });
}

function clearGetCache() {
  getCache.clear();
}

function requestHeaders() {
  return {
    'Content-Type': 'application/json',
  };
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError';
}

const RATE_LIMIT_MESSAGE = 'GitHub API rate limit reached';

/** Minutes until the GitHub API limit resets, or null when the error is not a rate limit (0 if unknown). */
export function rateLimitRetryMinutes(error: unknown): number | null {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  if (!message.includes(RATE_LIMIT_MESSAGE)) return null;
  const seconds = Number(message.match(/retry after (\d+) seconds/)?.[1]);
  return Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds / 60) : 0;
}

class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
    this.name = 'ApiError';
  }
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (response.ok) {
    const data = await response.json() as T;
    return data;
  } else {
    const errorText = await response.text();
    console.error(`[API Client] Error response for ${response.url}. Status: ${response.status}`);
    if (response.status === 429 && /rate limit/i.test(errorText)) {
      // The backend's 429 page is HTML; keep the GitHub rate-limit reason and retry time from it.
      const retryAfter = errorText.match(/retry after (\d+) seconds/i)?.[1];
      throw new ApiError(`${RATE_LIMIT_MESSAGE}${retryAfter ? `; retry after ${retryAfter} seconds` : ''}`, 429);
    }
    if (response.status >= 500 || response.headers.get('content-type')?.includes('text/html')) {
      throw new ApiError(`Server error (HTTP ${response.status})`, response.status);
    }
    throw new ApiError(errorText.slice(0, 500) || 'An API error occurred', response.status);
  }
}

export async function get<T>(
  path: string,
  params?: object
): Promise<T> {
  let urlString = `${API_BASE_URL}${path}`;

  if (params && Object.keys(params).length > 0) {
    const queryParams = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (
        value !== undefined &&
        value !== null &&
        ['string', 'number', 'boolean'].includes(typeof value)
      ) {
        queryParams.append(key, String(value));
      }
    });
    const query = queryParams.toString();
    if (query) {
    urlString += `?${query}`;
    }
  }

  const cacheEnabled = shouldCacheGet(path);
  if (cacheEnabled) {
    const cached = getCache.get(urlString);
    if (cached && cached.expiresAt > Date.now()) {
      return cached.data as T;
    }

    const inFlight = inFlightGets.get(urlString);
    if (inFlight) {
      return inFlight as Promise<T>;
    }
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT);

  const requestPromise = (async () => {
    const response = await fetch(urlString, {
      method: 'GET',
      headers: requestHeaders(),
      cache: 'no-store',
      signal: controller.signal,
    });

    const data = await handleResponse<T>(response);
    if (cacheEnabled) {
      setGetCache(urlString, path, data);
    }
    return data;
  })();

  if (cacheEnabled) {
    inFlightGets.set(urlString, requestPromise);
  }

  try {
    return await requestPromise;
  } catch (error: unknown) {
    if (isAbortError(error)) {
      console.error(`[API Client] GET Request timeout for ${urlString} after ${DEFAULT_TIMEOUT}ms`);
      throw new ApiError('Request timeout', 408);
    }
    throw error;
  } finally {
    if (cacheEnabled) {
      inFlightGets.delete(urlString);
    }
    clearTimeout(timeoutId);
  }
}

export async function post<T>(path: string, body: unknown, extraHeaders?: Record<string, string>): Promise<T> {
  const urlString = `${API_BASE_URL}${path}`;

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT);

  try {
    const response = await fetch(urlString, {
      method: 'POST',
      headers: { ...requestHeaders(), ...extraHeaders },
      body: JSON.stringify(body),
      cache: 'no-store',
      signal: controller.signal,
    });

    console.log(`[API Client] Received response for ${urlString}. Status: ${response.status}`);
    const data = await handleResponse<T>(response);
    clearGetCache();
    return data;
  } catch (error: unknown) {
    if (isAbortError(error)) {
      console.error(`[API Client] POST Request timeout for ${urlString} after ${DEFAULT_TIMEOUT}ms`);
      throw new ApiError('Request timeout', 408);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

export async function put<T>(path: string, body: unknown): Promise<T> {
  const urlString = `${API_BASE_URL}${path}`;

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), DEFAULT_TIMEOUT);

  try {
    const response = await fetch(urlString, {
      method: 'PUT',
      headers: requestHeaders(),
      body: JSON.stringify(body),
      cache: 'no-store',
      signal: controller.signal,
    });

    console.log(`[API Client] Received response for ${urlString}. Status: ${response.status}`);
    const data = await handleResponse<T>(response);
    clearGetCache();
    return data;
  } catch (error: unknown) {
    if (isAbortError(error)) {
      console.error(`[API Client] PUT Request timeout for ${urlString} after ${DEFAULT_TIMEOUT}ms`);
      throw new ApiError('Request timeout', 408);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}
