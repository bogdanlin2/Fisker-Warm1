// Stage 6 wire contract. This is internet TEST traffic, never a PKC packet.
export const TYPE = "CLIMATE_ON_TEST";
export const MODE = "TEST_ONLY";
export const RECEIPT_RANK = Object.freeze({DELIVERED:1, TEST_ACCEPTED:2, EXPIRED_REJECTED:2, INTERRUPTED_NO_RETRY:2});
const hex = /^[0-9a-f]{32}$/;
const macHex = /^[0-9a-f]{64}$/;
export function commandText(c) {
 return ["FW6", "COMMAND", c.pair, c.id, c.issued, c.expires, c.type, c.mode].join("\n");
}
export function receiptText(r) {
 return ["FW6", "RECEIPT", r.pair, r.id, r.commandMac, r.at, r.stage, r.vehicle].join("\n");
}
export function heartbeatText(h) {return ["FW6", "HEARTBEAT", h.pair, h.at, h.nonce, MODE].join("\n");}
export function commandShape(c, pair, now) {
 return c && c.v===1 && c.pair===pair && hex.test(c.id) && Number.isSafeInteger(c.issued) && Number.isSafeInteger(c.expires)
 && c.issued<=now+10 && c.issued>=now-60 && c.expires>now && c.expires>c.issued && c.expires-c.issued<=60
 && c.type===TYPE && c.mode===MODE && macHex.test(c.mac);
}
export function receiptShape(r, pair, id, commandMac, now) {
 return r && r.v===1 && r.pair===pair && r.id===id && r.commandMac===commandMac && r.vehicle==="NOT_IMPLEMENTED"
 && Object.hasOwn(RECEIPT_RANK,r.stage) && Number.isSafeInteger(r.at) && r.at<=now+10 && r.at>=0 && macHex.test(r.mac);
}
export function heartbeatShape(h, pair, now) {
 return h && h.v===1 && h.pair===pair && Number.isSafeInteger(h.at) && Math.abs(now-h.at)<=75 && hex.test(h.nonce) && macHex.test(h.mac);
}
export async function hash(s) {return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)))).map(b=>b.toString(16).padStart(2,"0")).join("");}
export function randomHex(n=32) {return Array.from(crypto.getRandomValues(new Uint8Array(n))).map(b=>b.toString(16).padStart(2,"0")).join("");}
export function equal(a,b) {if(typeof a!=="string" || typeof b!=="string" || a.length!==b.length)return false;let d=0;for(let i=0;i<a.length;i++)d|=a.charCodeAt(i)^b.charCodeAt(i);return d===0;}
