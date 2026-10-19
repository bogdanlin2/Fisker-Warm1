"use client";
import {useEffect, useRef, useState} from "react";
import {setupRequest} from "../lib/setup-client.mjs";

type Pair = {id: string; revoked: number};
type Bundle = {remote: {pair: string}; gateway: {pair: string}};

export default function Setup() {
  const [pairs, setPairs] = useState<Pair[]>([]);
  const [bundle, setBundle] = useState<Bundle | null>(null);
  const [message, setMessage] = useState("Натисніть кнопку нижче, щоб прив’язати свої телефони.");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loadingPairs, setLoadingPairs] = useState(true);
  const inFlight = useRef(false);

  async function list() {
    setLoadingPairs(true);
    try {
      const data = await setupRequest({action: "list"});
      if (!Array.isArray(data.pairs)) throw new Error("Сервер повернув некоректний список пристроїв.");
      setPairs(data.pairs);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не вдалося оновити список пристроїв.");
    } finally {setLoadingPairs(false);}
  }
  useEffect(() => {void list();}, []);

  async function create() {
    if (inFlight.current || bundle) return;
    inFlight.current = true;
    setBusy(true); setError(""); setMessage("Створюємо пару… Зачекайте, будь ласка.");
    try {
      const data = await setupRequest({action: "create"});
      if (!data.remote?.pair || data.remote.pair !== data.gateway?.pair) throw new Error("Не отримано обидва файли пари. Спробуйте оновити сторінку.");
      setBundle(data);
      setMessage("Пару створено. Тепер завантажте обидва файли.");
      void list();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не вдалося створити пару.");
      setMessage("Прив’язку не завершено.");
    } finally {inFlight.current = false; setBusy(false);}
  }

  function save(role: "remote" | "gateway") {
    if (!bundle) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(bundle[role])], {type: "application/json"}));
    const a = document.createElement("a");
    a.href = url; a.download = `Fisker-Warm-${role}-private.json`;
    document.body.appendChild(a); a.click(); a.remove();
    // Android can display a download prompt; do not invalidate its URL after only one second.
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    setMessage(`Файл ${role === "remote" ? "Remote" : "Gateway"} передано для завантаження. Перевірте «Завантаження» на телефоні.`);
  }

  async function revoke(pair: string) {
    if (inFlight.current) return;
    inFlight.current = true; setBusy(true); setError(""); setMessage("Відкликаємо доступ…");
    try {
      const r = await setupRequest({action: "revoke", pair});
      if (!r.revoked) throw new Error("Відкликання ще не підтверджено. Повторіть спробу.");
      if (bundle?.remote.pair === pair) setBundle(null);
      setMessage("Пару відкликано. Її старі файли більше не працюють.");
      void list();
    } catch (e) {setError(e instanceof Error ? e.message : "Не вдалося відкликати пару.");}
    finally {inFlight.current = false; setBusy(false);}
  }

  return <main className="setup-page">
    <header className="setup-header">
      <span className="test-badge">ТЕСТОВИЙ РЕЖИМ</span>
      <h1>Fisker Warm</h1>
      <p className="intro">Прив’яжіть телефон власника й телефон в автомобілі.</p>
    </header>

    <section className="setup-card" aria-labelledby="pair-heading">
      <h2 id="pair-heading">Прив’язка телефонів</h2>
      <ol className="setup-steps">
        <li>Створіть одну захищену пару.</li>
        <li>Імпортуйте <strong>Remote</strong> у S24 Ultra, а <strong>Gateway</strong> — у телефон в автомобілі.</li>
        <li>У Gateway натисніть «Запустити шлюз», у Remote — «CLIMATE ON · ТЕСТ».</li>
      </ol>
      <button type="button" className="button primary" disabled={busy || !!bundle} onClick={create} aria-busy={busy}>
        {busy ? "Виконуємо запит…" : bundle ? "Пару створено" : "Створити захищену пару"}
      </button>
      <p className="status-message" role="status" aria-live="polite">{message}</p>
      {error && <div className="error-message" role="alert"><p>{error}</p>
        <a href="/signin-with-chatgpt?return_to=%2F" target="_top">Увійти знову</a>
      </div>}
      {bundle && <div className="download-panel">
        <h3>Завантажте два файли</h3>
        <button type="button" className="button primary" onClick={() => save("remote")}>Завантажити Remote · для S24 Ultra</button>
        <button type="button" className="button secondary" onClick={() => save("gateway")}>Завантажити Gateway · для авто</button>
        <p className="muted">Збережіть обидва файли до закриття цієї сторінки. Після імпорту видаліть їх із завантажень. Не передавайте файли стороннім — вони містять приватні ключі.</p>
      </div>}
      <noscript><p className="error-message">Для прив’язки потрібен JavaScript. Відкрийте цю сторінку в Chrome з увімкненим JavaScript.</p></noscript>
    </section>

    <section className="setup-card" aria-labelledby="devices-heading">
      <div className="section-title"><h2 id="devices-heading">Прив’язані пристрої</h2>
        <button type="button" className="refresh-button" disabled={busy || loadingPairs} onClick={() => {setError(""); void list();}}>Оновити</button>
      </div>
      {loadingPairs ? <p className="muted" role="status">Оновлюємо список…</p> : pairs.length === 0 ? <p className="muted">Поки немає створених пар.</p> :
        <ul className="device-list">{pairs.map(pair => <li key={pair.id}>
          <div><strong>Пара {pair.id.slice(0, 8)}</strong><p className="muted">{pair.revoked ? "Доступ відкликано" : "Активна"}</p></div>
          {!pair.revoked && <button type="button" className="revoke-button" disabled={busy} onClick={() => void revoke(pair.id)}>Відкликати</button>}
        </li>)}</ul>}
    </section>
    <footer className="page-note"><strong>Це перевірка інтернет-доставки.</strong> Gateway підтверджує лише отримання тесту. Автомобільні команди й запуск клімату залишаються заблокованими.</footer>
  </main>;
}
