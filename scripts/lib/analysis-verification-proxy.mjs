// Verification-only fault: lose one response after the real core committed it.
import { createServer } from 'node:http';
let dropRun = null;
let failSource = null;
let holdSource = null;
let sourceHeld = false;
let dropProposal = null;
const server = createServer(async (request, response) => {
  try {
    if (request.url === '/health') { response.end('ready'); return; }
    const parts = [];
    let length = 0;
    for await (const chunk of request) {
      length += chunk.length;
      if (length > 4 * 1024 * 1024 + 65536) throw Error('limit');
      parts.push(chunk);
    }
    const body = Buffer.concat(parts);
    if (request.url === '/control/state' && request.headers['x-verification-control'] === process.env.CONTROL_TOKEN) {
      response.setHeader('content-type','application/json');response.end(JSON.stringify({ sourceHeld }));return;
    }
    if (request.url === '/control/hold-source' && request.headers['x-verification-control'] === process.env.CONTROL_TOKEN) {
      holdSource=JSON.parse(body).runId;sourceHeld=false;response.end('configured');return;
    }
    if (request.url === '/control/fail-source' && request.headers['x-verification-control'] === process.env.CONTROL_TOKEN) {
      failSource = JSON.parse(body).runId;
      response.end('configured'); return;
    }
    if (request.url === '/control/drop-result' && request.headers['x-verification-control'] === process.env.CONTROL_TOKEN) {
      dropRun = JSON.parse(body).runId;
      response.end('configured');
      return;
    }
    if (request.url === '/control/drop-proposal' && request.headers['x-verification-control'] === process.env.CONTROL_TOKEN) {
      const input=JSON.parse(body);
      if(!/^[a-f0-9-]{36}$/.test(input.runId)||!['document','complete'].includes(input.stage)) {response.writeHead(400).end();return;}
      dropProposal=input;response.end('configured');return;
    }
    if (!/^\/internal\/(analysis|proposal)-runs\/[a-f0-9-]+\//.test(request.url)) { response.writeHead(404).end(); return; }
    if (failSource && request.url.startsWith(`/internal/analysis-runs/${failSource}/documents/`) && request.url.endsWith('/source')) {
      response.writeHead(503, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      response.end('{"code":"SOURCE_UNAVAILABLE"}'); return;
    }
    if (holdSource && request.url.startsWith(`/internal/analysis-runs/${holdSource}/documents/`) && request.url.endsWith('/source')) {
      sourceHeld=true;const deadline=Date.now()+25000;
      while(holdSource && Date.now()<deadline) await new Promise(done=>setTimeout(done,100));
    }
    const upstream = await fetch(process.env.CORE_API_URL + request.url, {
      method: request.method, body, redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { authorization: request.headers.authorization ?? '', 'content-type': 'application/json' },
    });
    const bytes = Buffer.from(await upstream.arrayBuffer());
    if(dropProposal && upstream.ok && request.url.startsWith(`/internal/proposal-runs/${dropProposal.runId}/`)
      && ((dropProposal.stage==='complete' && request.url.endsWith('/complete'))
       ||(dropProposal.stage==='document' && request.url.endsWith('/checkpoints') && JSON.parse(body).stage==='document'))) {
      dropProposal=null;response.destroy();return;
    }
    if (dropRun && request.url === `/internal/analysis-runs/${dropRun}/results` && upstream.ok) {
      dropRun = null;
      response.destroy();
      return;
    }
    response.writeHead(upstream.status, { 'content-type': upstream.headers.get('content-type'),
      'content-length': bytes.length, 'cache-control': 'no-store' });
    response.end(bytes);
  } catch { response.destroy(); }
});
server.requestTimeout = 30000;
server.headersTimeout = 10000;
server.listen(8080, '0.0.0.0');
process.on('SIGTERM', () => { server.closeAllConnections(); server.close(() => process.exit(0)); });
