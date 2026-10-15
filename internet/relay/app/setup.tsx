"use client";
import {useState,useEffect} from "react";
export default function Setup(){
 const [pairs,setPairs]=useState<any[]>([]);
 async function list(){try{const r=await fetch("/api/setup",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({action:"list"})});if(r.ok)setPairs((await r.json()).pairs);}catch{}}
 useEffect(()=>{list();},[]);
 const [bundle,setBundle]=useState<any>(null),[message,setMessage]=useState("Пара ще не створена."),[busy,setBusy]=useState(false);
 async function create(){setBusy(true);try{const r=await fetch("/api/setup",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({action:"create"})});if(!r.ok)throw Error();setBundle(await r.json());list();setMessage("Пару створено. Завантажте обидва файли та імпортуйте у відповідні застосунки.");}catch{setMessage("Не вдалося створити пару. Оновіть вхід і повторіть.");}finally{setBusy(false);}}
 function save(role:string){const url=URL.createObjectURL(new Blob([JSON.stringify(bundle[role])],{type:"application/json"}));const a=document.createElement("a");a.href=url;a.download=`Fisker-Warm-${role}-private.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
 async function revoke(pair=bundle?.remote.pair){setBusy(true);try{const r=await fetch("/api/setup",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({action:"revoke",pair})});if(!r.ok)throw Error();if(bundle?.remote.pair===pair)setBundle(null);list();setMessage("Пару відкликано. Старі конфігурації більше не працюють.");}catch{setMessage("Помилка відкликання. Повторіть.");}finally{setBusy(false);}}
 return <main style={{maxWidth:680,margin:"8vh auto",padding:28,fontFamily:"system-ui",color:"#142b38"}}>
 <p style={{color:"#a45500",fontWeight:700}}>ЕТАП 6 · ТІЛЬКИ ТЕСТОВА ДОСТАВКА</p><h1 style={{fontSize:40}}>Fisker Warm</h1>
 <p>Приватне налаштування інтернет-шлюзу. Ця система не надсилає команд автомобілю та не запускає HVAC.</p>
 <ol><li>Створіть пару один раз.</li><li>Імпортуйте Remote-файл у S24 Ultra, Gateway-файл — у телефон в автомобілі.</li><li>У Gateway натисніть «Запустити шлюз», у Remote — «CLIMATE ON · ТЕСТ».</li></ol>
 <button disabled={busy||!!bundle} onClick={create}>Створити захищену пару</button>
 <p role="status">{message}</p>{bundle&&<><p>Файли містять приватні ключі доступу. Передавайте їх лише між власними пристроями, видаліть із завантажень після імпорту. Сервер не зберігає копій ключів.</p><button onClick={()=>save("remote")}>Завантажити Remote</button> <button onClick={()=>save("gateway")}>Завантажити Gateway</button><p><button disabled={busy} onClick={()=>revoke()}>Відкликати цю пару</button></p></>}
 <h2>Прив’язані пристрої</h2>{pairs.map(p=><p key={p.id}>{p.id.slice(0,8)} · {p.revoked?"відкликано":"активна пара"} {!p.revoked&&<button disabled={busy} onClick={()=>revoke(p.id)}>Відкликати</button>}</p>)}<p style={{marginTop:36,fontSize:14}}>Підтвердження Gateway означає лише прийняття тесту. PKC 21.3, стан автомобіля, запуск і вимкнення клімату тут не перевіряються. Купівля обладнання для цього тесту не потрібна.</p></main>;
}
