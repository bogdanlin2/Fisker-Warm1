import test from 'node:test';
import assert from 'node:assert/strict';
import {setupRequest} from '../lib/setup-client.mjs';

test('pair request remains same-origin POST with the owner API contract', async () => {
  let request;
  const result = await setupRequest({action:'create'}, {fetcher: async (url, options) => {
    request = {url, ...options};
    return Response.json({remote:{pair:'fixture'}, gateway:{pair:'fixture'}});
  }});
  assert.equal(request.url, '/api/setup');
  assert.equal(request.method, 'POST');
  assert.equal(request.credentials, 'same-origin');
  assert.equal(JSON.parse(request.body).action, 'create');
  assert.equal(result.remote.pair, 'fixture');
});
test('expired sign-in HTML is reported as an authentication error', async () => {
  await assert.rejects(setupRequest({action:'create'}, {fetcher:async()=>new Response('<html>Sign in</html>', {headers:{'content-type':'text/html'}})}), /поновити вхід/);
});
test('owner authorization failure is visible without leaking response content', async () => {
  await assert.rejects(setupRequest({action:'create'}, {fetcher:async()=>Response.json({error:'OWNER_REQUIRED',secret:'must-not-appear'},{status:403})}), e=>e.message.includes('поновити вхід')&&!e.message.includes('must-not-appear'));
});
test('unrecognized server error text is never shown as diagnostic data', async () => {
  await assert.rejects(setupRequest({action:'create'}, {fetcher:async()=>Response.json({error:'secret raw body'},{status:503})}), /Не вдалося виконати запит/);
});
test('hung request times out so the page can re-enable the action', async () => {
  const fetcher=async (_,options)=>new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError'))));
  await assert.rejects(setupRequest({action:'create'}, {fetcher,timeoutMs:5}), /Сервер не відповів/);
});
test('network loss gives a readable connection error', async () => {
  await assert.rejects(setupRequest({action:'list'}, {fetcher:async()=>{throw new TypeError('fetch failed')}}), /Немає зв’язку/);
});

test('malformed JSON never exposes its response text', async () => {
  await assert.rejects(setupRequest({action:'create'}, {fetcher:async()=>new Response('private-token-invalid-json',{headers:{'content-type':'application/json'}})}), e=>e.message.includes('некоректну відповідь')&&!e.message.includes('private-token'));
});
