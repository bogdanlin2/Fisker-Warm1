#!/usr/bin/env python3
"""Private relay internet self-test. Supply ephemeral credentials on hidden stdin, never argv/files.
Two HTTP clients emulate Remote/Gateway; this does NOT test Android or send vehicle traffic.
"""
import json,sys,time,secrets,hmac,hashlib,urllib.request,urllib.error,ssl,threading
from pathlib import Path
if sys.stdin.isatty():
 import termios
 attr=termios.tcgetattr(sys.stdin);attr[3]&=~termios.ECHO;termios.tcsetattr(sys.stdin,termios.TCSANOW,attr)
print('Ready for private relay test configuration on stdin (hidden).',flush=True)
args=json.loads(sys.stdin.readline());origin=args['origin'];site=args['siteToken'];admin=args['provisionKey'];checks=[]
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*a,**k):return None
opener=urllib.request.build_opener(NoRedirect,urllib.request.HTTPSHandler(context=ssl.create_default_context()))
def call(path,body,token=None,platform=True,extra=None):
 headers={'Content-Type':'application/json','Accept':'application/json'}
 if platform:headers['OAI-Sites-Authorization']='Bearer '+site
 if token:headers['Authorization']='Bearer '+token
 if extra:headers.update(extra)
 req=urllib.request.Request(origin+path,data=json.dumps(body).encode(),headers=headers,method='POST')
 try:
  with opener.open(req,timeout=35) as r:raw=r.read(65536);return r.status,json.loads(raw)
 except urllib.error.HTTPError as e:
  try:b=json.loads(e.read(65536))
  except Exception:b={}
  return e.code,b

def check(name,condition):
 if not condition:raise AssertionError(name)
 checks.append(name);print('PASS '+name,flush=True)
def now():return int(time.time())
def mac(c,text):return hmac.new(bytes.fromhex(c['macKey']),text.encode(),hashlib.sha256).hexdigest()
def cmd(c):
 t=now();x={'v':1,'pair':c['pair'],'id':secrets.token_hex(16),'issued':t,'expires':t+60,'type':'CLIMATE_ON_TEST','mode':'TEST_ONLY'};x['mac']=mac(c,'\n'.join(map(str,['FW6','COMMAND',x['pair'],x['id'],t,t+60,x['type'],x['mode']])));return x

def poll(c,wait=0):
 t=now();n=secrets.token_hex(16);h={'v':1,'pair':c['pair'],'at':t,'nonce':n};h['mac']=mac(c,'\n'.join(map(str,['FW6','HEARTBEAT',c['pair'],t,n,'TEST_ONLY'])));return call('/api/relay',{'action':'poll','pair':c['pair'],'heartbeat':h,'wait':wait},c['token'])
def status(c,x=None):return call('/api/relay',{'action':'status','pair':c['pair'],**({'id':x['id']} if x else {})},c['token'])
def submit(c,x):return call('/api/relay',{'action':'submit','pair':c['pair'],'command':x},c['token'])
def receipt(c,x,stage):
 t=now();r={'v':1,'pair':c['pair'],'id':x['id'],'commandMac':x['mac'],'at':t,'stage':stage,'vehicle':'NOT_IMPLEMENTED'};r['mac']=mac(c,'\n'.join(map(str,['FW6','RECEIPT',c['pair'],x['id'],x['mac'],t,stage,'NOT_IMPLEMENTED'])));return r

def signed_receipt(c,x,r):return r['commandMac']==x['mac'] and r['vehicle']=='NOT_IMPLEMENTED' and hmac.compare_digest(r['mac'],mac(c,'\n'.join(map(str,['FW6','RECEIPT',c['pair'],x['id'],x['mac'],r['at'],r['stage'],r['vehicle']]))))
if args.get('verifyClosed'):
 code,_=call('/api/setup',{'action':'create'},extra={'x-fw-provision':admin})
 check('temporary provisioning key disabled after final deployment',code==403)
 out=Path('build/provisioning-closed.json');out.parent.mkdir(exist_ok=True)
 out.write_text(json.dumps({'temporaryAdminDisabled':True,'httpStatus':code,'vehicleCommandsSent':0},indent=2)+'\n')
 sys.exit(0)
start=time.time();pair=None
try:
 check('Sites boundary rejects an unauthenticated internet caller',call('/api/relay',{'action':'status'},platform=False)[0] in (301,302,303,307,308,401,403))
 check('service token alone cannot provision owner credentials',call('/api/setup',{'action':'create'})[0]==403)
 check('forged browser identity cannot provision through service access',call('/api/setup',{'action':'create'},extra={'oai-authenticated-user-id':'forged-owner','Origin':origin})[0]==403)
 code,pair=call('/api/setup',{'action':'create'},extra={'x-fw-provision':admin});check('private temporary provisioning',code==200)
 remote=pair['remote'];gateway=pair['gateway'];x=cmd(remote)
 check('offline gateway rejects new work',submit(remote,x)[0]==409)
 check('outbound gateway HTTPS connection',poll(gateway)[0]==200)
 check('role separation rejects gateway submit',call('/api/relay',{'action':'submit','pair':gateway['pair'],'command':x},gateway['token'])[0]==403)
 check('wrong application token rejected',call('/api/relay',{'action':'status','pair':remote['pair']},secrets.token_hex(32))[0]==401)
 result={}
 def receive():result['value']=poll(gateway,20)
 thread=threading.Thread(target=receive);thread.start();time.sleep(0.5)
 t0=time.monotonic();check('remote HTTPS submission is durable',submit(remote,x)[0]==202);thread.join(30)
 check('long poll delivered the exact command',not thread.is_alive() and result['value'][0]==200 and result['value'][1]['commands'][0]['mac']==x['mac'])
 latency=round(time.monotonic()-t0,3)
 check('retry reuses original durable command ID',submit(remote,x)[1].get('duplicate') is True)
 for stage in ('DELIVERED','TEST_ACCEPTED'):
  r=receipt(gateway,x,stage);check('gateway signed '+stage,call('/api/relay',{'action':'receipt','pair':gateway['pair'],'receipt':r},gateway['token'])[0]==200)
  s=status(remote,x)[1];ack=s['command']['receipt'];check('remote independently verified '+stage,signed_receipt(remote,x,ack) and ack['stage']==stage)
 check('terminal test is not redelivered',poll(gateway)[1]['commands']==[])
 # A real elapsed offline interval tests presence and TTL on the deployed service.
 stale=cmd(remote);check('prepare disconnect/expiry test',submit(remote,stale)[0]==202)
 print('Waiting for the 60-second command TTL and 75-second presence TTL; no gateway polling.',flush=True)
 time.sleep(77)
 code,s=status(remote,stale);check('real offline timeout is visible',code==200 and s['online'] is False)
 check('real stale test expires without acceptance',s['command']['expired'] is True and s['command']['receipt'] is None)
 check('offline commands remain rejected',submit(remote,cmd(remote))[0]==409)
 check('reconnection never delivers expired work',poll(gateway)[1]['commands']==[])
 check('old request replay cannot become new work',submit(remote,stale)[1].get('duplicate') is True)
 check('owner revocation accepted',call('/api/setup',{'action':'revoke','pair':remote['pair']},extra={'x-fw-provision':admin})[0]==200)
 check('revoked Remote rejected',status(remote)[0]==401);check('revoked Gateway rejected',poll(gateway)[0]==401)
 result={'origin':origin,'testedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'checksPassed':len(checks),'checks':checks,'longPollDeliverySeconds':latency,'elapsedSeconds':round(time.time()-start,1),'physicalAndroidDevices':False,'screenOffVerified':False,'vehicleCommandsSent':0,'climateStarted':False,'temporaryPairRevoked':True}
 out=Path('build/deployed-relay-test.json');out.parent.mkdir(exist_ok=True);out.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result),flush=True)
finally:
 if pair:call('/api/setup',{'action':'revoke','pair':pair['remote']['pair']},extra={'x-fw-provision':admin})
