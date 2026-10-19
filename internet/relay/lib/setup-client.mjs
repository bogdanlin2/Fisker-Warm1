// Owner-browser API only. Never print or persist returned configuration secrets.
const messages = {
  OWNER_REQUIRED: "Потрібно поновити вхід власника. Натисніть «Увійти знову» нижче.",
  SERVICE_ACCESS_UNCONFIGURED: "Сервер поки не готовий до прив’язки. Спробуйте пізніше.",
  DATABASE_UNAVAILABLE: "Сервер тимчасово недоступний. Спробуйте ще раз.",
  REQUEST_FAILED: "Сервер не завершив запит. Спробуйте ще раз.",
};
export async function setupRequest(body, {fetcher = globalThis.fetch, timeoutMs = 15000} = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const r = await fetcher("/api/setup", {
      method: "POST",
      credentials: "same-origin",
      headers: {"Content-Type": "application/json", "Accept": "application/json"},
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!r.headers.get("content-type")?.includes("application/json")) {
      throw new Error(messages.OWNER_REQUIRED);
    }
    const data = await r.json();
    if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("Сервер повернув некоректну відповідь. Повторіть спробу.");
    if (!r.ok) {
      throw new Error(messages[data?.error] || "Не вдалося виконати запит. Повторіть спробу.");
    }
    return data;
  } catch (error) {
    if (controller.signal.aborted) throw new Error("Сервер не відповів за 15 секунд. Перевірте інтернет і повторіть спробу.");
    if (error instanceof SyntaxError) throw new Error("Сервер повернув некоректну відповідь. Повторіть спробу.");
    if (error instanceof TypeError) throw new Error("Немає зв’язку із сервером. Перевірте інтернет і повторіть спробу.");
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}
