// Isolated HTTPS fixture. Never used by production composition.
import {createServer} from 'node:https';
import {readFileSync,appendFileSync,existsSync,writeFileSync} from 'node:fs';
let calls=0;
const root='/verify';
createServer({pfx:readFileSync(root+'/model.p12'),passphrase:process.env.FIXTURE_TLS_PASSWORD},(req,res)=>{
  if(req.url==='/health'){res.end('ok');return;}
  let body='';req.on('data',chunk=>{body+=chunk;if(body.length>80000)req.destroy();});
  req.on('end',()=>{
    const input=JSON.parse(body);calls++;
    const mode=existsSync(root+'/model-mode')?readFileSync(root+'/model-mode','utf8').trim():'success';
    appendFileSync(root+'/model-calls.jsonl',JSON.stringify({call:calls,mode,model:input.model,stage:input.response_format?.json_schema?.name})+'\n');
    if(mode==='rate-once'){writeFileSync(root+'/model-mode','success');res.writeHead(429);res.end();return;}
    if(mode==='unauthorized'){res.writeHead(401);res.end();return;}
    if(mode==='hold'){const timer=setInterval(()=>{
      if(readFileSync(root+'/model-mode','utf8').trim()!=='hold'){clearInterval(timer);reply();}
    },100);res.on('close',()=>clearInterval(timer));return;}
    reply();
    function reply(){
      res.setHeader('content-type','application/json');
      res.end(JSON.stringify({model:input.model,usage:{prompt_tokens:100,completion_tokens:20},
        choices:[{finish_reason:'stop',message:{content:JSON.stringify({fields:[],lines:[],warnings:['EMPTY_DOCUMENT']})}}]}));
    }
  });
}).listen(8443,'0.0.0.0');
