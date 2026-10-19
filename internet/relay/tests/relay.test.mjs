import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHmac} from 'node:crypto';
import {handle} from '../lib/relay.mjs';
import {commandText,receiptText,heartbeatText} from '../lib/protocol.mjs';
const t=1700000000;
function adapter(db){return {prepare(sql){let args=[];return {bind(...x){args=x;return this;},async first(){return db.prepare(sql).get(...args)||null;},async all(){return {results:db.prepare(sql).all(...args)};},async run(){const r=db.prepare(sql).run(...args);return {meta:{changes:r.changes}};}};}};}
function setup(){const dir=mkdtempSync(join(tmpdir(),'fw6-'));const file=join(dir,'relay.db');let db=new DatabaseSync(file);const sql=readFileSync(new URL('../drizzle/0000_nebulous_mastermind.sql',import.meta.url),'utf8');db.exec(sql);const env={DB:adapter(db),FW_ORIGIN:'https://relay.example',FW_SITE_SERVICE_TOKEN:'fixture-platform-token-not-valid-in-production',FW_PROVISION_KEY:'fixture-only-admin'};return {env,db,file,dir,close(){db.close();rmSync(dir,{recursive:true,force:true});}};}
async function call(env,body,token=null,options={now:t,noWait:true},path='/api/relay',extra={}){const headers={'content-type':'application/json',...extra};if(token)headers.authorization=`Bearer ${token}`;const r=await handle(new Request('https://relay.example'+path,{method:'POST',headers,body:JSON.stringify(body)}),env,options);return {status:r.status,body:await r.json(),headers:r.headers};}
async function pair(f){return (await call(f.env,{action:'create'},null,undefined,'/api/setup',{'oai-authenticated-user-id':'owner','origin':'https://relay.example'})).body;}
function sign(key,text){return createHmac('sha256',Buffer.from(key,'hex')).update(text).digest('hex');}
function command(c,id='11'.repeat(16),issued=t){const x={v:1,pair:c.pair,id,issued,expires:issued+60,type:'CLIMATE_ON_TEST',mode:'TEST_ONLY'};x.mac=sign(c.macKey,commandText(x));return x;}
function heartbeat(c,at=t){const h={v:1,pair:c.pair,at,nonce:'22'.repeat(16)};h.mac=sign(c.macKey,heartbeatText(h));return h;}
async function poll(f,c,at=t){return call(f.env,{action:'poll',pair:c.pair,heartbeat:heartbeat(c,at)},c.token,{now:at,noWait:true});}
function receipt(c,cmd,stage='TEST_ACCEPTED'){let r={v:1,pair:c.pair,id:cmd.id,commandMac:cmd.mac,at:t+1,stage,vehicle:'NOT_IMPLEMENTED'};r.mac=sign(c.macKey,receiptText(r));return r;}
async function submit(f,c,cmd,at=t){return call(f.env,{action:'submit',pair:c.pair,command:cmd},c.token,{now:at,noWait:true});}

test('owner provisioning rejects anonymous and cross-origin requests',async()=>{let f=setup();try{
 assert.equal((await call(f.env,{action:'create'},null,undefined,'/api/setup')).status,403);
 assert.equal((await call(f.env,{action:'create'},null,undefined,'/api/setup',{'oai-authenticated-user-id':'owner','origin':'https://evil.example'})).status,403);
 const r=await call(f.env,{action:'create'},null,undefined,'/api/setup',{'oai-authenticated-user-id':'owner','origin':'https://relay.example'});assert.equal(r.status,200);assert.notEqual(r.body.remote.token,r.body.gateway.token);
 assert.equal(f.db.prepare('SELECT remote_hash FROM pairs').get().remote_hash.includes(r.body.remote.token),false);
 assert.equal(r.headers.get('cache-control'),'no-store');
}finally{f.close();}});
test('wrong credential, pair and role are rejected',async()=>{let f=setup();try{const p=await pair(f);const c=p.remote;
 assert.equal((await call(f.env,{action:'status',pair:c.pair},'33'.repeat(32))).status,401);
 assert.equal((await call(f.env,{action:'status',pair:'44'.repeat(16)},c.token)).status,401);
 assert.equal((await call(f.env,{action:'submit',pair:c.pair,command:command(c)},p.gateway.token)).status,403);
 assert.equal((await call(f.env,{action:'poll',pair:c.pair,heartbeat:heartbeat(c)},c.token)).status,403);
}finally{f.close();}});
test('offline gateway is not queued; fresh presence permits test delivery',async()=>{let f=setup();try{const p=await pair(f),c=p.remote,cmd=command(c);
 assert.equal((await submit(f,c,cmd)).body.error,'GATEWAY_OFFLINE');assert.equal(f.db.prepare('SELECT COUNT(*) n FROM commands').get().n,0);
 await poll(f,p.gateway);assert.equal((await submit(f,c,cmd)).status,202);
 const result=await poll(f,p.gateway);assert.equal(result.body.commands[0].mac,cmd.mac);
 const status=await call(f.env,{action:'status',pair:c.pair,id:cmd.id},c.token);assert.equal(status.body.command.receipt,null);assert.equal(status.body.online,true);
}finally{f.close();}});
test('same ID is idempotent, altered ID payload conflicts, only one pending command',async()=>{let f=setup();try{const p=await pair(f),c=p.remote,cmd=command(c);await poll(f,p.gateway);await submit(f,c,cmd);
 assert.equal((await submit(f,c,cmd)).body.duplicate,true);assert.equal(f.db.prepare('SELECT COUNT(*) n FROM commands').get().n,1);
 assert.equal((await submit(f,c,{...cmd,expires:t+59})).status,409);
 assert.equal((await submit(f,c,command(c,'55'.repeat(16)))).body.error,'COMMAND_PENDING');
}finally{f.close();}});
test('receipt distinguishes delivered and test accepted; vehicle state is never HVAC ON',async()=>{let f=setup();try{const p=await pair(f),c=p.remote,cmd=command(c);await poll(f,p.gateway);await submit(f,c,cmd);
 const body=r=>({action:'receipt',pair:c.pair,receipt:r});
 assert.equal((await call(f.env,body(receipt(c,cmd,'DELIVERED')),p.gateway.token)).status,200);
 let s=await call(f.env,{action:'status',pair:c.pair,id:cmd.id},c.token);assert.equal(s.body.command.receipt.stage,'DELIVERED');
 assert.equal((await call(f.env,body(receipt(c,cmd)),p.gateway.token)).status,200);
 assert.equal((await poll(f,p.gateway)).body.commands.length,0);
 s=await call(f.env,{action:'status',pair:c.pair,id:cmd.id},c.token);assert.equal(s.body.command.receipt.stage,'TEST_ACCEPTED');assert.equal(s.body.command.receipt.vehicle,'NOT_IMPLEMENTED');
 assert.equal((await call(f.env,body({...receipt(c,cmd),vehicle:'HVAC_ON'}),p.gateway.token)).status,400);
 assert.equal((await call(f.env,body(receipt(c,cmd,'DELIVERED')),p.gateway.token)).status,409);
 assert.equal((await call(f.env,body(receipt(c,cmd)),p.gateway.token)).status,200);
}finally{f.close();}});
test('expired or future command cannot be newly queued and expires while offline',async()=>{let f=setup();try{const p=await pair(f),c=p.remote;await poll(f,p.gateway);
 assert.equal((await submit(f,c,command(c,'66'.repeat(16),t-60))).status,400);
 assert.equal((await submit(f,c,command(c,'66'.repeat(16),t+11))).status,400);
 const cmd=command(c);await submit(f,c,cmd);
 assert.equal((await poll(f,p.gateway,t+61)).body.commands.length,0);
 let s=await call(f.env,{action:'status',pair:c.pair,id:cmd.id},c.token,{now:t+76,noWait:true});assert.equal(s.body.command.expired,true);assert.equal(s.body.online,true); // last heartbeat at t+61
 s=await call(f.env,{action:'status',pair:c.pair},c.token,{now:t+137,noWait:true});assert.equal(s.body.online,false);
 assert.equal((await submit(f,c,cmd,t+137)).body.duplicate,true);assert.equal(f.db.prepare('SELECT COUNT(*) n FROM commands').get().n,1);
}finally{f.close();}});
test('database reopen preserves commands, receipts and idempotency',async()=>{let f=setup();try{const p=await pair(f),c=p.remote,cmd=command(c);await poll(f,p.gateway);await submit(f,c,cmd);
 await call(f.env,{action:'receipt',pair:c.pair,receipt:receipt(c,cmd)},p.gateway.token);
 const reopened=new DatabaseSync(f.file);f.env.DB=adapter(reopened);
 assert.equal((await submit(f,c,cmd)).body.duplicate,true);
 const s=await call(f.env,{action:'status',pair:c.pair,id:cmd.id},c.token);assert.equal(s.body.command.receipt.stage,'TEST_ACCEPTED');reopened.close();
}finally{f.close();}});
test('owner revocation disables both role tokens',async()=>{let f=setup();try{const p=await pair(f),c=p.remote;await poll(f,p.gateway);
 assert.equal((await call(f.env,{action:'revoke',pair:c.pair},null,undefined,'/api/setup',{'oai-authenticated-user-id':'owner','origin':'https://relay.example'})).status,200);
 assert.equal((await poll(f,p.gateway)).status,401);assert.equal((await call(f.env,{action:'status',pair:c.pair},c.token)).status,401);
}finally{f.close();}});
test('concurrent pending inserts cannot create two new commands',async()=>{let f=setup();try{const p=await pair(f),c=p.remote;await poll(f,p.gateway);
 const r=await Promise.all([submit(f,c,command(c,'77'.repeat(16))),submit(f,c,command(c,'88'.repeat(16)))]);assert.equal(r.filter(x=>x.status===202).length,1);assert.equal(f.db.prepare('SELECT COUNT(*) n FROM commands').get().n,1);
}finally{f.close();}});
test('invalid JSON and oversized chunked body are bounded',async()=>{const f=setup();try{
 const r=await handle(new Request('https://relay.example/api/relay',{method:'POST',headers:{'content-type':'application/json'},body:'{'}),f.env);assert.equal(r.status,400);
 const big=await handle(new Request('https://relay.example/api/relay',{method:'POST',headers:{'content-type':'application/json'},body:'x'.repeat(20000)}),f.env);assert.equal(big.status,413);
}finally{f.close();}});

test('obsolete service provisioning secret never authorizes an owner operation',async()=>{const f=setup();try{const r=await call(f.env,{action:'create'},null,undefined,'/api/setup',{'x-fw-provision':'fixture-only-admin'});assert.equal(r.status,403);}finally{f.close();}});
