import { NextRequest } from "next/server";

const CORE_API_URL = process.env.CORE_API_URL || "http://127.0.0.1:8080";

async function proxyRequest(request: NextRequest, paramsPromise: Promise<{ path: string[] }>) {
  const { path } = await paramsPromise;
  const targetPath = path.join("/");
  const targetUrl = new URL(`/api/${targetPath}${request.nextUrl.search}`, CORE_API_URL);

  const forwardHeaders = new Headers();
  const auth = request.headers.get("authorization");
  if (auth) {
    forwardHeaders.set("authorization", auth);
  }
  const traceId = request.headers.get("x-trace-id");
  if (traceId) {
    forwardHeaders.set("x-trace-id", traceId);
  }
  const contentType = request.headers.get("content-type");
  if (contentType) {
    forwardHeaders.set("content-type", contentType);
  }
  const accept = request.headers.get("accept");
  if (accept) {
    forwardHeaders.set("accept", accept);
  }

  let body: BodyInit | null = null;
  if (["POST", "PUT", "PATCH"].includes(request.method)) {
    try {
      body = await request.text();
    } catch {
      body = null;
    }
  }

  try {
    const res = await fetch(targetUrl.toString(), {
      method: request.method,
      headers: forwardHeaders,
      body,
      cache: "no-store",
    });

    const responseHeaders = new Headers();
    const resTrace = res.headers.get("x-trace-id");
    if (resTrace) {
      responseHeaders.set("x-trace-id", resTrace);
    }
    const resContentType = res.headers.get("content-type");
    if (resContentType) {
      responseHeaders.set("content-type", resContentType);
    }

    const responseBody = await res.text();
    return new Response(responseBody, {
      status: res.status,
      headers: responseHeaders,
    });
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "Service Unavailable";
    return Response.json(
      { code: "CORE_API_UNAVAILABLE", message: `Could not reach core-api at ${CORE_API_URL}: ${message}` },
      { status: 503 }
    );
  }
}

export async function GET(request: NextRequest, props: { params: Promise<{ path: string[] }> }) {
  return proxyRequest(request, props.params);
}

export async function POST(request: NextRequest, props: { params: Promise<{ path: string[] }> }) {
  return proxyRequest(request, props.params);
}

export async function PUT(request: NextRequest, props: { params: Promise<{ path: string[] }> }) {
  return proxyRequest(request, props.params);
}

export async function DELETE(request: NextRequest, props: { params: Promise<{ path: string[] }> }) {
  return proxyRequest(request, props.params);
}
