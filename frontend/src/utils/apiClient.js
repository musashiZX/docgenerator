/**
 * Traced fetch wrapper for all frontend → backend JSON calls.
 *
 * Reads X-Trace-Id from the response and can append client-side execution
 * notes (e.g. batch-replace apply stats) via POST /api/trace/{id}/append.
 */

export async function apiFetch(url, options = {}) {
  const res = await fetch(url, options);
  const traceId = res.headers.get('X-Trace-Id');

  let data = null;
  const contentType = res.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    data = await res.clone().json().catch(() => null);
  }

  if (traceId) {
    console.debug(`[api-trace] ${options.method || 'GET'} ${url} → trace ${traceId}`);
  }

  return { res, data, traceId };
}

/** Fire-and-forget: append client-side execution data to a backend trace folder. */
export async function appendTraceExtra(apiBase, traceId, name, data) {
  if (!traceId || !apiBase) return;
  try {
    await fetch(`${apiBase}/api/trace/${encodeURIComponent(traceId)}/append`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, data }),
    });
  } catch (err) {
    console.warn('[api-trace] failed to append client extra:', err);
  }
}

/** POST JSON and return parsed body; throws on non-OK with server detail. */
export async function apiPostJson(apiBase, path, body, clientExtra = null) {
  const { res, data, traceId } = await apiFetch(`${apiBase}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    const detail = data?.detail || res.statusText;
    throw new Error(typeof detail === 'string' ? detail : `Server error ${res.status}`);
  }

  if (clientExtra && traceId) {
    appendTraceExtra(apiBase, traceId, clientExtra.name, clientExtra.data);
  }

  return { data, traceId };
}

/** GET JSON; returns null data on failure without throwing. */
export async function apiGetJson(apiBase, path) {
  const { res, data } = await apiFetch(`${apiBase}${path}`);
  if (!res.ok) return null;
  return data;
}
