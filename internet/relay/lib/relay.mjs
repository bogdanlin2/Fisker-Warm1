import {hash,randomHex,equal,commandShape,receiptShape,heartbeatShape,RECEIPT_RANK} from "./protocol.mjs";
const headers={"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store","X-Content-Type-Options":"nosniff","Referrer-Policy":"no-referrer"};
const reply=(body,status=200)=>new Response(JSON.stringify(body),{status,headers});
const fail=(code,status=400)=>reply({error:code},status);
const seconds=()=>Math.floor(Date.now()/1000);
export async function handle(request,env,options={}) {
 const now=options.now??seconds();
 try {
  if(request.method!=="POST")return fail("METHOD",405);
  if(!request.headers.get("content-type")?.startsWith("application/json"))return fail("JSON_REQUIRED",415);
  if(Number(request.headers.get("content-length")||0)>16384)return fail("TOO_LARGE",413);
  // Stream limit also applies when Content-Length is omitted/chunked.
  const reader=request.body?.getReader();let bytes=0;const chunks=[];
  if(reader)while(true){const {done,value}=await reader.read();if(done)break;bytes+=value.byteLength;if(bytes>16384){await reader.cancel();return fail("TOO_LARGE",413);}chunks.push(value);}
  const all=new Uint8Array(bytes);let offset=0;for(const c of chunks){all.set(c,offset);offset+=c.length;}
  let body;try{body=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(all));}catch{return fail("INVALID_JSON");}
  const path=new URL(request.url).pathname;
  const db=env.DB;if(!db)return fail("DATABASE_UNAVAILABLE",503);
  if(path==="/api/setup"){
   const site=new URL(env.FW_ORIGIN||request.url).origin;
   const user=request.headers.get("oai-authenticated-user-id");
   // Provisioning is browser-owner-only. No service/admin-key bypass exists, even if a stale runtime secret remains.
   if(!user || request.headers.get("origin")!==site)return fail("OWNER_REQUIRED",403);
   const owner=user;
   if(body.action==="list"){
    const rows=await db.prepare("SELECT id,created,revoked,last_seen FROM pairs WHERE owner=? ORDER BY created DESC LIMIT 100").bind(owner).all();
    return reply({pairs:rows.results});
   }
   if(body.action==="revoke"){
    if(!/^[0-9a-f]{32}$/.test(body.pair||""))return fail("PAIR");
    await db.prepare("UPDATE pairs SET revoked=1 WHERE id=? AND owner=?").bind(body.pair,owner).run();
    return reply({revoked:true});
   }
   if(body.action!=="create")return fail("ACTION");
   if(!env.FW_SITE_SERVICE_TOKEN)return fail("SERVICE_ACCESS_UNCONFIGURED",503);
   const pair=randomHex(16),remote=randomHex(),gateway=randomHex(),key=randomHex();
   await db.prepare("INSERT INTO pairs(id,owner,remote_hash,gateway_hash,created) VALUES(?,?,?,?,?)").bind(pair,owner,await hash(remote),await hash(gateway),now).run();
   const base={v:1,pair,relay:site,siteToken:env.FW_SITE_SERVICE_TOKEN,macKey:key,mode:"TEST_ONLY"};
   // One-time configuration response to the authenticated owner. Keys are never retained in the database or application source.
   return reply({remote:{...base,role:"remote",token:remote},gateway:{...base,role:"gateway",token:gateway}});
  }
  if(path!=="/api/relay")return fail("NOT_FOUND",404);
  const token=request.headers.get("authorization")?.match(/^Bearer ([0-9a-f]{64})$/)?.[1];
  if(!token || !/^[0-9a-f]{32}$/.test(body.pair||""))return fail("AUTH",401);
  const row=await db.prepare("SELECT * FROM pairs WHERE id=? AND revoked=0").bind(body.pair).first();
  if(!row)return fail("AUTH",401);
  const th=await hash(token);const role=equal(th,row.remote_hash)?"remote":equal(th,row.gateway_hash)?"gateway":null;
  if(!role)return fail("AUTH",401);
  if(body.action==="status" && role==="remote"){
   const cmd=typeof body.id==="string"?await db.prepare("SELECT envelope,receipt,expires FROM commands WHERE pair=? AND id=?").bind(row.id,body.id).first():null;
   return reply({serverTime:now,online:now-row.last_seen<=75,heartbeat:row.heartbeat?JSON.parse(row.heartbeat):null,
    command:cmd?{envelope:JSON.parse(cmd.envelope),receipt:cmd.receipt?JSON.parse(cmd.receipt):null,expired:cmd.expires<=now}:null});
  }
  if(body.action==="submit" && role==="remote"){
   const c=body.command;
   // Exact retry of an existing command returns its durable result, even after its TTL; it never creates new work.
   if(c && /^[0-9a-f]{32}$/.test(c.id||"")){
    const old=await db.prepare("SELECT envelope,receipt FROM commands WHERE pair=? AND id=?").bind(row.id,c.id).first();
    if(old){if(JSON.stringify(JSON.parse(old.envelope))!==JSON.stringify(c))return fail("ID_CONFLICT",409);return reply({stored:true,id:c.id,duplicate:true});}
   }
   if(!commandShape(c,row.id,now))return fail("COMMAND_OR_EXPIRY");
   if(now-row.last_seen>75)return fail("GATEWAY_OFFLINE",409);
   // Atomic conditional insert prevents concurrent submissions from creating an unbounded pending queue.
   const result=await db.prepare("INSERT OR IGNORE INTO commands(pair,id,issued,expires,envelope) SELECT ?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM commands WHERE pair=? AND rank<2 AND expires>?)")
    .bind(row.id,c.id,c.issued,c.expires,JSON.stringify(c),row.id,now).run();
   if(!result.meta?.changes)return fail("COMMAND_PENDING",409);
   return reply({stored:true,id:c.id,duplicate:false},202);
  }
  if(body.action==="poll" && role==="gateway"){
   if(!heartbeatShape(body.heartbeat,row.id,now))return fail("HEARTBEAT");
   await db.prepare("UPDATE pairs SET heartbeat=?,last_seen=? WHERE id=? AND revoked=0").bind(JSON.stringify(body.heartbeat),now,row.id).run();
   const wait=options.noWait?0:Math.min(20,Math.max(0,Number(body.wait)||0));
   const deadline=Date.now()+wait*1000;
   while(true){
    // Re-check revocation during a long poll.
    const active=await db.prepare("SELECT revoked FROM pairs WHERE id=?").bind(row.id).first();
    if(!active || active.revoked)return fail("AUTH",401);
    const rows=await db.prepare("SELECT envelope FROM commands WHERE pair=? AND expires>? AND rank<2 ORDER BY issued LIMIT 1").bind(row.id,secondsForTest(now,options)).all();
    if(rows.results.length || Date.now()>=deadline)return reply({serverTime:secondsForTest(now,options),commands:rows.results.map(x=>JSON.parse(x.envelope))});
    await new Promise(resolve=>setTimeout(resolve,2000));
   }
  }
  if(body.action==="receipt" && role==="gateway"){
   const r=body.receipt;const c=r && await db.prepare("SELECT envelope,receipt,rank FROM commands WHERE pair=? AND id=?").bind(row.id,r.id).first();
   if(!c || !receiptShape(r,row.id,r.id,JSON.parse(c.envelope).mac,now))return fail("RECEIPT");
   const rank=RECEIPT_RANK[r.stage];
   if(c.rank>=rank){if(c.receipt && JSON.parse(c.receipt).stage!==r.stage)return fail("RECEIPT_CONFLICT",409);return reply({recorded:true});}
   await db.prepare("UPDATE commands SET receipt=?,rank=? WHERE pair=? AND id=? AND rank<?").bind(JSON.stringify(r),rank,row.id,r.id,rank).run();
   return reply({recorded:true});
  }
  return fail("ROLE_OR_ACTION",403);
 }catch{return fail("REQUEST_FAILED",503);}
}
function secondsForTest(now,options){return options.now??seconds();}
