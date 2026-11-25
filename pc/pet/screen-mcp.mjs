// MCP-сервер «экран» для мини-Claude.
//  • всегда: screenshot (снимок делает сам мини-Claude через Electron desktopCapturer);
//  • только если пользователь выбрал режим «🛠️ управлять» (SCREEN_CONTROL=1): мышь и клавиатура через nut.js.
// Перед каждым действием спрашивает «мозг», не нажал ли пользователь «Стоп». Не больше 40 действий за задачу.
import { createRequire } from "node:module";
import { createInterface } from "node:readline";

const require = createRequire(import.meta.url);
const BRAIN = "http://127.0.0.1:8787";
const KEY = process.env.SCREEN_KEY || "";
const CONTROL = process.env.SCREEN_CONTROL === "1";
const MAX_ACTIONS = 40;
let geo = null;      // последний снимок: картинка width×height ↔ область экрана bounds (в пикселях Windows)
let actions = 0;

const nut = CONTROL ? require("@nut-tree-fork/nut-js") : null;
if (nut) { nut.mouse.config.autoDelayMs = 40; nut.keyboard.config.autoDelayMs = 15; }

const headers = { "x-screen-key": KEY, "Content-Type": "application/json" };
async function brain(path, body) {
  const r = await fetch(BRAIN + path, body ? { method: "POST", headers, body: JSON.stringify(body) } : { headers });
  return { ok: r.ok, j: await r.json() };
}
const log = (text) => brain("/screen/log", { text }).catch(() => {});

function toScreen(x, y) {
  if (!geo) throw new Error("Сначала сделай screenshot — координаты считаются по последнему снимку.");
  const b = geo.bounds;
  return new nut.Point(Math.round(b.x + (x * b.width) / geo.width), Math.round(b.y + (y * b.height) / geo.height));
}

// Клавиатура. Буквы библиотека переводит в клавиши по ТЕКУЩЕЙ раскладке — при русской латиница не печатается,
// а Ctrl+буква не срабатывает. Поэтому: текст — через буфер обмена и Shift+Insert, сочетания — клавишами без букв.
const ln = CONTROL ? require("@nut-tree-fork/libnut-win32") : null;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const MODS = { ctrl: "control", control: "control", shift: "shift", alt: "alt" };
const NAMED = { enter: "enter", return: "enter", esc: "escape", escape: "escape", tab: "tab", backspace: "backspace",
  delete: "delete", del: "delete", up: "up", down: "down", left: "left", right: "right", home: "home", end: "end",
  pageup: "pageup", pagedown: "pagedown", space: "space", insert: "insert" };
for (let i = 1; i <= 12; i++) NAMED["f" + i] = "f" + i;
// частые сочетания с буквами → то же самое без букв
const SUBST = {
  "ctrl+v": [["insert", ["shift"]]], "ctrl+c": [["insert", ["control"]]], "ctrl+x": [["delete", ["shift"]]],
  "ctrl+a": [["home", ["control"]], ["end", ["control", "shift"]]],
};

async function typeText(text) {
  let old = null;
  try { old = await nut.clipboard.getContent(); } catch {}
  await nut.clipboard.setContent(text);
  await sleep(80);
  ln.keyTap("insert", ["shift"]);           // вставка, не зависящая от раскладки
  await sleep(250);
  if (old != null) setTimeout(() => nut.clipboard.setContent(old).catch(() => {}), 1000); // вернуть буфер обмена
}

/** Нажать сочетание. Возвращает предупреждение, если сочетание с буквой (может не сработать при русской раскладке). */
async function pressCombo(combo) {
  const c = combo.toLowerCase().replace(/\s+/g, "");
  if (SUBST[c]) { for (const [k, m] of SUBST[c]) ln.keyTap(k, m); return ""; }
  const parts = c.split("+");
  if (parts.length === 1 && (parts[0] === "win" || parts[0] === "windows")) {
    await nut.keyboard.pressKey(nut.Key.LeftSuper); await nut.keyboard.releaseKey(nut.Key.LeftSuper); return "";
  }
  const mods = [], keys = [];
  for (const p of parts) {
    if (MODS[p]) mods.push(MODS[p]);
    else if (p === "win" || p === "windows") mods.push("command");
    else keys.push(p);
  }
  if (keys.length !== 1) throw new Error("нужна ровно одна основная клавиша: " + combo);
  const k = keys[0];
  if (NAMED[k]) { ln.keyTap(NAMED[k], mods); return ""; }
  if (/^[a-z0-9]$/.test(k)) {
    ln.keyTap(k, mods);
    return " Внимание: сочетания с буквами могут не сработать при русской раскладке — проверь снимком. Текст набирай инструментом type.";
  }
  throw new Error("не знаю клавишу: " + k);
}

const TOOLS = [
  { name: "screenshot", description: "Снимок экрана (монитор, где курсор, или по номеру с 0)." + (CONTROL ? " Координаты для click/scroll — в пикселях этой картинки." : ""),
    inputSchema: { type: "object", properties: { monitor: { type: "integer" } } } },
  ...(!CONTROL ? [] : [
    { name: "click", description: "Клик мышью в точку (x, y) последнего снимка.",
      inputSchema: { type: "object", required: ["x", "y"], properties: {
        x: { type: "integer" }, y: { type: "integer" }, button: { type: "string", enum: ["left", "right"] }, double: { type: "boolean" } } } },
    { name: "type", description: "Вставить текст в активное поле (через буфер обмена; работает с любой раскладкой и кириллицей). Сначала кликни в поле.",
      inputSchema: { type: "object", required: ["text"], properties: { text: { type: "string" } } } },
    { name: "key", description: "Клавиша или сочетание: enter, esc, tab, backspace, delete, стрелки, home, end, f1–f12, win, alt+tab, alt+f4, ctrl+a/c/v/x (сделаны без букв). Сочетания с другими буквами могут не сработать при русской раскладке.",
      inputSchema: { type: "object", required: ["keys"], properties: { keys: { type: "string" } } } },
    { name: "scroll", description: "Прокрутка в точке (x, y): amount > 0 — вниз, < 0 — вверх.",
      inputSchema: { type: "object", required: ["x", "y", "amount"], properties: { x: { type: "integer" }, y: { type: "integer" }, amount: { type: "integer" } } } },
    { name: "wait", description: "Подождать 1–10 секунд (пока что-то загрузится).",
      inputSchema: { type: "object", required: ["seconds"], properties: { seconds: { type: "number" } } } },
  ]),
];

async function call(name, a = {}) {
  if (name === "screenshot") {
    const { ok, j } = await brain(`/screen/shot${Number.isInteger(a.monitor) ? `?monitor=${a.monitor}` : ""}`);
    if (!ok) return err(j.error || "не получилось снять экран");
    geo = j;
    return { content: [
      { type: "image", data: j.data, mimeType: "image/jpeg" },
      { type: "text", text: `Монитор ${j.index + 1} из ${j.count}, картинка ${j.width}×${j.height}.` },
    ] };
  }
  if (!CONTROL) return err("Пользователь разрешил только смотреть.");
  const { j: st } = await brain("/screen/state");
  if (!st.active || st.stop) return err("Пользователь нажал «Стоп». Больше ничего не делай и коротко подведи итог.");
  if (++actions > MAX_ACTIONS) return err(`Лимит ${MAX_ACTIONS} действий. Остановись и расскажи, что успел и что осталось.`);

  switch (name) {
    case "click": {
      const p = toScreen(a.x, a.y);
      log(a.double ? "🖱️ двойной клик" : a.button === "right" ? "🖱️ правый клик" : "🖱️ клик");
      await nut.mouse.setPosition(p);
      if (a.double) await nut.mouse.doubleClick(nut.Button.LEFT);
      else if (a.button === "right") await nut.mouse.rightClick();
      else await nut.mouse.leftClick();
      return ok("Готово. Сделай screenshot, чтобы проверить.");
    }
    case "type": {
      const t = String(a.text ?? "");
      log(`⌨️ печатаю «${t.slice(0, 30)}${t.length > 30 ? "…" : ""}»`);
      await typeText(t);
      return ok("Вставлено. Проверь снимком.");
    }
    case "key": {
      log(`⌨️ ${a.keys}`);
      const warn = await pressCombo(String(a.keys));
      return ok("Нажато." + warn);
    }
    case "scroll": {
      const n = Math.max(-15, Math.min(15, a.amount | 0));
      log("🖱️ прокрутка");
      await nut.mouse.setPosition(toScreen(a.x, a.y));
      if (n > 0) await nut.mouse.scrollDown(n * 3); else await nut.mouse.scrollUp(-n * 3);
      return ok("Прокручено.");
    }
    case "wait":
      await new Promise((r) => setTimeout(r, Math.max(1, Math.min(10, Number(a.seconds) || 1)) * 1000));
      return ok("Подождал.");
  }
  return err("Нет такого инструмента");
}
const ok = (text) => ({ content: [{ type: "text", text }] });
const err = (text) => ({ content: [{ type: "text", text }], isError: true });

// ---- JSON-RPC по stdio (одна строка — одно сообщение) ----
const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");
createInterface({ input: process.stdin }).on("line", async (line) => {
  let m; try { m = JSON.parse(line); } catch { return; }
  if (m.id === undefined) return;
  try {
    if (m.method === "initialize") send({ jsonrpc: "2.0", id: m.id, result: {
      protocolVersion: m.params?.protocolVersion || "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "screen", version: "1.1.0" } } });
    else if (m.method === "tools/list") send({ jsonrpc: "2.0", id: m.id, result: { tools: TOOLS } });
    else if (m.method === "tools/call") {
      let r; try { r = await call(m.params.name, m.params.arguments); } catch (e) { r = err("Ошибка: " + e.message); }
      send({ jsonrpc: "2.0", id: m.id, result: r });
    }
    else if (m.method === "ping") send({ jsonrpc: "2.0", id: m.id, result: {} });
    else send({ jsonrpc: "2.0", id: m.id, error: { code: -32601, message: "method not found" } });
  } catch (e) { send({ jsonrpc: "2.0", id: m.id, error: { code: -32603, message: e.message } }); }
});
