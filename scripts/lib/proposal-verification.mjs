import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {join,basename} from 'node:path';
import {readFileSync,writeFileSync,existsSync} from 'node:fs';

// Reuses the parser verification's isolated DB, Core, broker and cleanup ledger.
export async function verifyProposal({repo,output,api,sql,container,command,wait,docker,rabbit,worker,
  normal,workerToken,rabbitUser,rabbitPassword,run,record,coreUrl}) {
  await command(['stop','-t','30',worker]);
  const proxyName=basename(output)+'-proxy';assert(/^im-p208-[a-f0-9]{8}-proxy$/.test(proxyName));
  const proxyUrl='http://'+await command(['port',proxyName,'8080/tcp']);
  const controlToken=await command(['exec',proxyName,'node','-p','process.env.CONTROL_TOKEN']);
  const tlsPassword=randomUUID();
  assert.equal(await run('keytool',['-genkeypair','-alias','model','-keyalg','RSA','-storetype','PKCS12',
    '-keystore',join(output,'model.p12'),'-storepass',tlsPassword,'-keypass',tlsPassword,
    '-dname','CN=model','-ext','SAN=dns:model','-validity','2','-noprompt'],{cwd:repo,timeoutMs:30000}),0);
  assert.equal(await run('keytool',['-exportcert','-rfc','-alias','model','-keystore',join(output,'model.p12'),
    '-storepass',tlsPassword,'-file',join(output,'ca.pem')],{cwd:repo,timeoutMs:30000}),0);
  await container('model',process.env.NODE_RUNTIME_IMAGE??'node:24-alpine',
    ['--entrypoint','node','-v',output+':/verify','-v',join(repo,'scripts/lib/proposal-verification-model.mjs')+':/model.mjs:ro'],
    {FIXTURE_TLS_PASSWORD:tlsPassword},['/model.mjs']);
  const shell='set -e\npython -m venv /runtime/venv\nV=/runtime/venv/bin\n$V/python -m pip install --disable-pip-version-check --cache-dir /cache -r /w/requirements-dev.txt\nmkdir -p /runtime/pkg\ntar --exclude="__pycache__" --exclude="*.pyc" -cf - -C /w src pyproject.toml README.md | tar -xf - -C /runtime/pkg\n$V/python -m pip install --no-build-isolation --no-deps /runtime/pkg\ncd /tmp\nexec $V/ai-worker consume-proposals';
  const proposalWorker=await container('proposal-worker','python:3.12-slim',
    ['--memory','512m','--pids-limit','128','-v',join(repo,'ai-worker')+':/w:ro','-v',output+':/verify:ro',
      '-v',join(repo,'output/p3/linux/cache/pip')+':/cache'],
    {CORE_API_URL:'http://proxy:8080',ANALYSIS_WORKER_TOKEN:workerToken,ANALYSIS_AI_ENABLED:'true',
      ANALYSIS_RABBIT_HOST:'rabbit',ANALYSIS_RABBIT_USERNAME:rabbitUser,ANALYSIS_RABBIT_PASSWORD:rabbitPassword,
      AI_MODEL_URL:'https://model:8443/chat',AI_MODEL_KEY:'fixture-only-key',AI_MODEL_NAME:'fixture',
      AI_INPUT_PRICE_PER_MILLION:'1',AI_OUTPUT_PRICE_PER_MILLION:'2',AI_COST_CURRENCY:'USD',AI_COST_CEILING:'0.1',
      SSL_CERT_FILE:'/verify/ca.pem'},['sh','-c',shell]);
  const modelCalls=()=>existsSync(join(output,'model-calls.jsonl'))?readFileSync(join(output,'model-calls.jsonl'),'utf8').trim().split('\n').filter(Boolean).length:0;
  const mode=value=>writeFileSync(join(output,'model-mode'),value);
  const reserve=async()=>{
    await api('POST',`/api/invoice-cases/${normal.id}/match`,{requestId:randomUUID()},'operator');
    const version=Number(await sql(`select version from invoice_case where id='${normal.id}'`));
    return api('POST',`/api/invoice-cases/${normal.id}/proposals`,{requestId:randomUUID(),expectedCaseVersion:version},'operator');
  };
  const finished=async(id,status,timeout=120000)=>wait('proposal '+status,async()=>await sql(`select status from proposal_run where id='${id}'`)===status,timeout);
  const empty=()=>wait('proposal ACK',async()=>{
    const r=await docker.run(['exec','--user','rabbitmq',rabbit,'rabbitmqctl','-q','list_queues','name','messages_ready','messages_unacknowledged']);
    return r.code===0&&/invoice\.proposal\.requests\s+0\s+0/.test(r.stdout);
  });
  mode('success');const first=await reserve();await finished(first.id,'COMPLETED');await empty();
  assert.equal(modelCalls(),1);assert.equal(await sql(`select count(*) from proposal_step where run_id='${first.id}' and stage not like 'tool:%'`),'5');
  const proof=await api('GET',`/api/invoice-cases/${normal.id}/proposals/${first.id}`,undefined,'approver');
  assert.equal(proof.run.status,'COMPLETED');
  const coreNormal=JSON.parse(await sql(`select payload::text from match_result where id=(select match_result_id from proposal_run where id='${first.id}')`)).normal;
  assert.equal(proof.payload.resolution.result.recommendation,coreNormal?'REVIEW_REQUIRED':'INSUFFICIENT_EVIDENCE');
  assert(proof.payload.resolution.result.warnings.includes('DOCUMENT_REVIEW'));
  const payload=await sql(`select payload::text from proposal_request_outbox where id='${first.id}'`);
  const publish='import os,sys,json,pika;p=json.loads(sys.argv[1]);c=pika.BlockingConnection(pika.ConnectionParameters(host="rabbit",credentials=pika.PlainCredentials(os.environ["ANALYSIS_RABBIT_USERNAME"],os.environ["ANALYSIS_RABBIT_PASSWORD"]),socket_timeout=2,stack_timeout=5));ch=c.channel();ch.confirm_delivery();ch.basic_publish(exchange="invoice.proposal",routing_key="ai-review-v1",body=sys.argv[1].encode(),mandatory=True,properties=pika.BasicProperties(content_type="application/json",content_encoding="UTF-8",type="InvoiceProposalRequested",message_id=p["eventId"],delivery_mode=2));c.close()';
  const duplicated=await docker.exec(proposalWorker,['/runtime/venv/bin/python','-c',publish,payload]);assert.equal(duplicated.code,0);
  await empty();assert.equal(modelCalls(),1);
  record('installed Linux proposal worker + HTTPS fixture + Core + broker: completion and exact replay without another model call');
  if(process.env.VERIFY_PROPOSAL_UI_ONLY!=='true') {
  mode('rate-once');const retried=await reserve();await finished(retried.id,'COMPLETED');await empty();
  assert.equal(await sql(`select execution_attempt from proposal_run where id='${retried.id}'`),'2');
  assert.equal(await sql(`select reserved_calls from proposal_run where id='${retried.id}'`),'2');
  assert.equal(await sql(`select count(*) from proposal_failure where run_id='${retried.id}' and error_code='AI_RATE_LIMIT'`),'1');
  assert.equal(modelCalls(),3);
  record('real HTTPS 429: durable retry dispatch and cumulative uncertain call budget');
  mode('unauthorized');const failed=await reserve();await finished(failed.id,'FAILED');await empty();
  assert.equal(await sql(`select execution_attempt from proposal_run where id='${failed.id}'`),'1');
  assert.equal(await sql(`select error_code from proposal_run where id='${failed.id}'`),'AI_CONFIGURATION');
  assert.equal(modelCalls(),4);
  record('real HTTPS authentication failure: permanent failure without repeated provider calls');
  for(const stage of ['document','complete']) {
    await command(['stop','-t','15',proposalWorker]);mode('success');const lost=await reserve();
    const configured=await fetch(proxyUrl+'/control/drop-proposal',{method:'POST',headers:{'x-verification-control':controlToken},
      body:JSON.stringify({runId:lost.id,stage}),signal:AbortSignal.timeout(5000)});assert(configured.ok);
    await command(['start',proposalWorker]);await finished(lost.id,'COMPLETED');await empty();
    assert.equal(await sql(`select reserved_calls from proposal_run where id='${lost.id}'`),'1');
    assert.equal(await sql(`select execution_attempt from proposal_run where id='${lost.id}'`),stage==='document'?'2':'1');
    assert.equal(modelCalls(),stage==='document'?5:6);
    record('real '+stage+' response lost after DB commit: saved checkpoint replay does not repeat the model call');
  }
  mode('hold');const abandoned=await reserve();await wait('model request before worker kill',async()=>modelCalls()===7);
  await command(['kill',proposalWorker]);
  assert.equal(await sql(`select reserved_calls from proposal_run where id='${abandoned.id}'`),'1');
  mode('success');await command(['start',proposalWorker]);await finished(abandoned.id,'COMPLETED',180000);await empty();
  assert.equal(await sql(`select execution_attempt from proposal_run where id='${abandoned.id}'`),'2');
  assert.equal(await sql(`select reserved_calls from proposal_run where id='${abandoned.id}'`),'2');
  assert.equal(modelCalls(),8);
  record('worker killed after the real model request: lease reclaim preserves uncertain reservation and completes once');
  mode('hold');const stale=await reserve();await wait('in-flight model request',async()=>modelCalls()===9);
  await api('POST',`/api/invoice-cases/${normal.id}/match`,{requestId:randomUUID()},'operator');mode('success');
  await finished(stale.id,'STALE');await empty();
  assert.equal(await sql(`select count(*) from proposal where id='${stale.id}'`),'0');
  assert.equal(await sql('select count(*) from payment_request'),'0');assert.equal(await sql('select count(*) from receipt_allocation'),'0');
  record('new human match during real model I/O: stale result cannot complete or create business effects');
  }
  if(process.env.VERIFY_UI_HOLD_SECONDS||existsSync(join(output,'ui-request'))){
    const seconds=Number(process.env.VERIFY_UI_HOLD_SECONDS??300);assert(Number.isInteger(seconds)&&seconds>0&&seconds<=300);
    writeFileSync(join(output,'ui-ready.json'),JSON.stringify({coreUrl,caseId:normal.id,proposalId:first.id}));
    console.log('INFO proposal UI inspection ready: '+output);
    const deadline=Date.now()+seconds*1000;
    while(Date.now()<deadline&&!existsSync(join(output,'ui-release')))await new Promise(resolve=>setTimeout(resolve,400));
  }
}
