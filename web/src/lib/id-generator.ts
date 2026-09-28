// Generates technically idempotent request IDs and client-side trace IDs.

export function generateRequestId(prefix = "req"): string {
  const ts = Date.now();
  const rand = Math.random().toString(36).substring(2, 10);
  return `${prefix}-${ts}-${rand}`;
}

export function generateTraceId(prefix = "trc"): string {
  const ts = Date.now();
  const rand = Math.random().toString(36).substring(2, 10);
  return `${prefix}-${ts}-${rand}`;
}
