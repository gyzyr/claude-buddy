// Мини-Claude для рабочего стола (Electron).
// Бегает по верхнему краю панели задач на всех мониторах, кодит, когда работает Claude Code,
// машет «!», когда задача готова, по клику — чат. В этом же процессе живёт «мозг» для телефона.
import { app, BrowserWindow, screen, ipcMain, Menu, Tray, nativeImage, Notification, shell, desktopCapturer } from "electron";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { execFile } from "node:child_process";
import { existsSync, appendFileSync, unlinkSync } from "node:fs";
import { homedir } from "node:os";
import { createRequire } from "node:module";

const here = dirname(fileURLToPath(import.meta.url));

// Журнал запуска/ошибок: %APPDATA%\claude-pet\pet.log — чтобы понять, почему не стартовал автозапуск.
const LOG = join(app.getPath("userData"), "pet.log");
const log = (...a) => { try { appendFileSync(LOG, `${new Date().toISOString()} ${a.join(" ")}\n`); } catch {} };
log("start", JSON.stringify(process.argv), "cwd=" + process.cwd());
process.on("uncaughtException", (e) => log("uncaught", e?.stack || e));
process.on("unhandledRejection", (e) => log("rejection", e?.stack || e));

// ---- экономим ресурсы ----
app.disableHardwareAcceleration();                       // без отдельного GPU-процесса
app.commandLine.appendSwitch("js-flags", "--max-old-space-size=96");
app.commandLine.appendSwitch("disable-features", "CalculateNativeWinOcclusion,SpareRendererForSitePerProcess");
app.commandLine.appendSwitch("enable-features", "NetworkServiceInProcess");   // сеть — в основном процессе
app.commandLine.appendSwitch("in-process-gpu");                                // и отрисовка тоже
app.commandLine.appendSwitch("renderer-process-limit", "2");
if (!app.requestSingleInstanceLock()) { log("already running — exit"); app.exit(0); }

// «Мозг» (HTTP-сервер для телефона + статус Claude Code + чат) — в этом же процессе.
const brain = await import("../brain-server.mjs");

const SIZE = 104;          // окно питомца, px
const FEET = 0.82;         // где подошвы (доля высоты)
let pet, tray, chatWin = null;
let x = 0, targetX = 0, left = false;
let act = "idle", restTicks = 20, sleeping = false, lastActivity = "idle", lastProject = null;

function floorAt(px) {
  const d = screen.getDisplayNearestPoint({ x: Math.round(px), y: 0 });
  return d.workArea.y + d.workArea.height;              // верхний край панели задач
}
function span() {
  const ds = screen.getAllDisplays();
  return [Math.min(...ds.map((d) => d.bounds.x)), Math.max(...ds.map((d) => d.bounds.x + d.bounds.width))];
}
function onScreen(px) {
  return screen.getAllDisplays().some((d) => px >= d.bounds.x && px < d.bounds.x + d.bounds.width);
}
let yFree = null; // пока несут или падает — своя высота, иначе стоит на панели задач
function place() {
  const y = yFree != null ? Math.round(yFree) : floorAt(x + SIZE / 2) - Math.round(SIZE * FEET);
  pet.setBounds({ x: Math.round(x), y, width: SIZE, height: SIZE });
}
function pickTarget() {
  const [a, b] = span();
  for (let i = 0; i < 10; i++) {
    const t = a + Math.random() * (b - a - SIZE);
    if (onScreen(t + SIZE / 2)) { targetX = t; return; }
  }
}
function send(state) { if (pet && !pet.isDestroyed()) pet.webContents.send("state", { ...state, gaze: gaze() }); }

/** Куда смотреть на экран: стоит справа → влево-вверх (-1), слева → вправо-вверх (1), по центру → вверх (0). */
function gaze() {
  const d = screen.getDisplayNearestPoint({ x: Math.round(x + SIZE / 2), y: floorAt(x) - 10 }).bounds;
  const rel = (x + SIZE / 2 - d.x) / d.width;
  return rel > 0.62 ? -1 : rel < 0.38 ? 1 : 0;
}

function createPet() {
  const wa = screen.getPrimaryDisplay().workArea;
  x = targetX = wa.x + wa.width * 0.7;
  pet = new BrowserWindow({
    width: SIZE, height: SIZE, transparent: true, frame: false, resizable: false, movable: false,
    skipTaskbar: true, alwaysOnTop: true, focusable: false, hasShadow: false, show: false,
    backgroundColor: "#00000000",
    webPreferences: { preload: join(here, "preload.cjs"), backgroundThrottling: false, spellcheck: false },
  });
  pet.setAlwaysOnTop(true, "screen-saver");             // выше панели задач
  pet.setVisibleOnAllWorkspaces(true);
  pet.loadFile(join(here, "pet.html"));
  pet.webContents.on("console-message", (e) => { if (e.level === "error" || e.level === "warning") log("pet.html:", e.message); });
  pet.once("ready-to-show", () => { place(); pet.showInactive(); });
}

// ---- не лезем поверх полноэкранных приложений (видео, игры, презентации) ----
let libnut = null;
try { libnut = createRequire(import.meta.url)("@nut-tree-fork/libnut-win32"); } catch (e) { log("libnut:", e?.message); }
let fsHidden = false;
const hwnd = (w) => { try { return w.getNativeWindowHandle().readUInt32LE(0); } catch { return 0; } };
/** Активное окно занимает весь монитор питомца целиком (вместе с местом панели задач)? Развёрнутое окно — нет. */
function fullscreenUnderPet() {
  if (!libnut) return false;
  try {
    const h = libnut.getActiveWindow();
    if (!h || BrowserWindow.getAllWindows().some((w) => !w.isDestroyed() && hwnd(w) === h)) return false; // наше окно
    const title = libnut.getWindowTitle(h);
    if (!title || title === "Program Manager") return false;               // рабочий стол
    const r = screen.screenToDipRect(null, libnut.getWindowRect(h));
    const d = screen.getDisplayNearestPoint({ x: Math.round(x + SIZE / 2), y: Math.round(floorAt(x) - 10) }).bounds;
    return r.x <= d.x && r.y <= d.y && r.x + r.width >= d.x + d.width && r.y + r.height >= d.y + d.height;
  } catch { return false; }
}
function checkFullscreen() {
  if (!pet || pet.isDestroyed() || yFree != null) return;
  const fs = fullscreenUnderPet();
  // Не прячем окно (после hide/show прозрачное окно перестаёт ловить клики) — просто опускаем его под полноэкранное приложение.
  const wins = [pet, popupWin].filter((w) => w && !w.isDestroyed());
  if (fs && !fsHidden) { fsHidden = true; for (const w of wins) w.setAlwaysOnTop(false); log("fullscreen app — ухожу под него"); }
  else if (!fs && fsHidden) { fsHidden = false; for (const w of wins) { w.setAlwaysOnTop(true, "screen-saver"); w.moveTop(); } log("fullscreen закрыт — снова сверху"); }
  else if (fsHidden && popupWin && !popupWin.isDestroyed() && popupWin.isAlwaysOnTop()) popupWin.setAlwaysOnTop(false);
}

// ---- поведение ----
function tick() {
  checkFullscreen();
  const s = brain.pcStatus();
  const hour = new Date().getHours();
  if (s.activity !== lastActivity) {
    if (s.activity === "alert" && Notification.isSupported())
      new Notification({ title: "Claude", body: `Задача на ПК готова${s.project ? ` — «${s.project}»` : ""}`, silent: true }).show();
    lastActivity = s.activity;
  }
  lastProject = s.project;

  if (yFree != null) return; // несут мышью или падает — не мешаем
  if (looking) { act = "look"; send({ act, left, project: lastProject }); return; } // стоит спиной, смотрит на экран
  const alerts = brain.getPhoneAlerts();
  if (codeNote && Date.now() - codeNote.time > 10 * 60_000) codeNote = null; // записка Claude Code живёт 10 минут
  updatePopup(codeNote ? [...alerts, codeNote] : alerts);
  if (alerts.length && !sleeping) { act = "alert"; send({ act, left, project: lastProject }); return; } // машет: кто-то ждёт ответа
  if (sleeping) act = "sleep";
  else if (s.activity === "coding") act = "coding";
  else if (s.activity === "alert") act = "alert";
  else if (act === "coding" || act === "alert") { act = "idle"; restTicks = 10; }
  else if (act !== "walk") {
    if (restTicks > 0) restTicks--;
    else if (hour >= 1 && hour < 7) act = "sleep";
    else { pickTarget(); act = "walk"; startWalking(); }
  }
  send({ act, left, project: lastProject });
}

let walkTimer = null;
function startWalking() {
  if (walkTimer) return;
  walkTimer = setInterval(() => {
    if (act !== "walk") { clearInterval(walkTimer); walkTimer = null; return; }
    const dx = targetX - x, speed = 3;
    if (Math.abs(dx) <= speed) {
      x = targetX;
      act = Math.random() < 0.2 ? "sleep" : "idle";
      restTicks = act === "sleep" ? 25 : 3 + Math.floor(Math.random() * 10);
      send({ act, left, project: lastProject });
    } else {
      const nl = dx < 0;
      if (nl !== left) { left = nl; send({ act, left, project: lastProject }); }
      x += Math.sign(dx) * speed;
    }
    place();
  }, 40);
}

// ---- чат ----
function openChat() {
  const alertProject = lastActivity === "alert" ? (lastProject || "") : "";
  brain.ackAlert(); tick();                               // клик = «увидел»: перестаёт прыгать сразу
  if (chatWin && !chatWin.isDestroyed()) { chatWin.show(); chatWin.focus(); return; }
  const d = screen.getDisplayNearestPoint({ x: Math.round(x), y: 0 }).workArea;
  const w = 380, h = 480;
  chatWin = new BrowserWindow({
    width: w, height: h, x: Math.max(d.x + 8, Math.min(Math.round(x) + SIZE / 2 - w / 2, d.x + d.width - w - 8)),
    y: floorAt(x) - SIZE - h, alwaysOnTop: true, autoHideMenuBar: true, title: "Claude", backgroundColor: "#FAF3EC",
    webPreferences: { preload: join(here, "preload.cjs"), spellcheck: false },
  });
  chatWin.loadFile(join(here, "chat.html"), { query: { alert: alertProject } });
  chatWin.on("closed", () => { chatWin = null; });     // окно уничтожается — память освобождается
}

ipcMain.handle("chat", async (_e, messages) => {
  brain.ackAlert(); tick();                               // начал отвечать — тоже «увидел»
  try {
    const ctx = { localTime: new Date().toLocaleString("ru-RU", { weekday: "long", hour: "2-digit", minute: "2-digit", day: "numeric", month: "long" }), from: "pc" };
    return (await brain.chat({ messages, context: ctx })).reply;
  } catch (e) { return `Не получилось спросить Claude Code: ${e.message}`; }
});
// ---- облачко над головой: важное сообщение с телефона (мессенджеры, почта) ----
// размер подсказывает само облачко: короткий текст — узкое, длинный — до 320 в ширину и выше
let popupWin = null, popupId = null, popupH = 86, popupW = 320;
const popupPos = () => ({ x: Math.round(x) + SIZE / 2 - Math.round(popupW / 2), y: floorAt(x) - SIZE - popupH + 22 });
function updatePopup(alerts) {
  const top = alerts.at(-1);
  if (!top || looking) {
    if (popupWin && !popupWin.isDestroyed()) popupWin.destroy();
    popupWin = null; popupId = null; return;
  }
  const w = popupW, h = popupH;
  const pos = popupPos();
  const data = { ...top, more: alerts.length - 1 };
  if (!popupWin || popupWin.isDestroyed()) {
    popupWin = new BrowserWindow({
      width: w, height: h, ...pos, transparent: true, frame: false, resizable: false, movable: false, skipTaskbar: true,
      focusable: false, alwaysOnTop: true, hasShadow: false, show: false, backgroundColor: "#00000000",
      webPreferences: { preload: join(here, "preload.cjs"), spellcheck: false },
    });
    popupWin.setAlwaysOnTop(true, "screen-saver");
    popupWin.loadFile(join(here, "popup.html"));
    popupWin.webContents.once("did-finish-load", () => { popupWin?.webContents.send("popup", data); popupWin?.showInactive(); });
    popupId = top.id;
    if (Notification.isSupported() && !String(top.id).startsWith("code-")) new Notification({ title: `${top.app} · ${top.who}`, body: top.summary, silent: false }).show();
  } else {
    popupWin.setPosition(pos.x, pos.y);
    if (popupId !== top.id || data.more !== popupWin._more) { popupWin.webContents.send("popup", data); popupId = top.id; }
  }
  popupWin._more = data.more;
}
ipcMain.on("popup-dismiss", (_e, id) => { if (codeNote?.id === id) codeNote = null; else brain.dismissPhoneAlert(id); tick(); });
// облачко растёт вверх: нижний край (хвостик над головой) остаётся на месте
ipcMain.on("popup-size", (_e, h, w) => {
  popupH = Math.max(60, Math.min(260, Math.round(h)));
  if (w) popupW = Math.max(140, Math.min(320, Math.round(w)));
  if (popupWin && !popupWin.isDestroyed()) popupWin.setBounds({ ...popupPos(), width: popupW, height: popupH });
});

// ---- «посмотри на экран»: Claude поворачивается к экрану, смотрит снимки, отвечает в чате ----
let looking = false, statusWin = null;

// снимок монитора (где курсор или по номеру) — штатный desktopCapturer Electron, до 1366 px по ширине
brain.setScreenshotProvider(async (monitor) => {
  const displays = screen.getAllDisplays();
  const cur = screen.getDisplayNearestPoint(screen.getCursorScreenPoint());
  const idx = Number.isInteger(monitor) && displays[monitor] ? monitor : Math.max(0, displays.findIndex((d) => d.id === cur.id));
  const d = displays[idx];
  const k = Math.min(1, 1366 / d.size.width);
  const size = { width: Math.round(d.size.width * k), height: Math.round(d.size.height * k) };
  const sources = await desktopCapturer.getSources({ types: ["screen"], thumbnailSize: size });
  const src = sources.find((s) => s.display_id === String(d.id)) || sources[idx] || sources[0];
  if (!src) throw new Error("экран недоступен");
  return { data: src.thumbnail.toJPEG(75).toString("base64"), index: idx, count: displays.length, width: size.width, height: size.height,
    bounds: d.bounds }; // где этот монитор на экране — по нему MCP-сервер переводит клики из картинки в экран
});

function showStatus(text) {
  if (!statusWin || statusWin.isDestroyed()) {
    const w = 300, h = 44;
    statusWin = new BrowserWindow({
      width: w, height: h, x: Math.round(x) + SIZE / 2 - w / 2, y: floorAt(x) - SIZE - h + 8,
      transparent: true, frame: false, resizable: false, movable: false, skipTaskbar: true, focusable: false,
      alwaysOnTop: true, hasShadow: false, show: false, backgroundColor: "#00000000",
      webPreferences: { preload: join(here, "preload.cjs"), spellcheck: false },
    });
    statusWin.setAlwaysOnTop(true, "screen-saver");
    statusWin.loadFile(join(here, "status.html"));
    statusWin.once("ready-to-show", () => statusWin?.showInactive());
    statusWin.webContents.once("did-finish-load", () => statusWin?.webContents.send("status", text));
  } else statusWin.webContents.send("status", text);
}

function stopLooking(why = "?") {
  if (!looking) return;
  log("stop look:", why);
  brain.screenStop();
  showStatus("✋ Останавливаюсь…");
}

ipcMain.handle("screen", (_e, a) => lookAtScreen(a));
async function lookAtScreen({ text, history, control = false }) {
  if (looking) return "Я уже смотрю на экран — подожди чуть-чуть.";
  brain.ackAlert();
  looking = true; act = "look"; send({ act, left, project: lastProject });
  if (chatWin && !chatWin.isDestroyed()) chatWin.hide();      // чтобы чат не загораживал экран
  showStatus(control ? "🛠️ Управляю компьютером… (клик по мне — стоп)" : "👀 Смотрю на экран… (клик по мне — стоп)");
  try { brain.screenStart({ text, history, control }); }
  catch (e) { looking = false; statusWin?.destroy(); chatWin?.show(); return "Не получилось: " + e.message; }
  const reply = await new Promise((resolve) => {
    const iv = setInterval(() => {
      const s = brain.screenStatus();
      if (!s) return;
      if (s.running) { if (!s.stopped && s.log.length) showStatus(`${s.log.at(-1)} (клик по мне — стоп)`); return; }
      clearInterval(iv); resolve(s.reply);
    }, 600);
  });
  looking = false; act = "idle"; restTicks = 6; send({ act, left, project: lastProject });
  if (statusWin && !statusWin.isDestroyed()) statusWin.destroy();
  statusWin = null;
  if (chatWin && !chatWin.isDestroyed()) { chatWin.show(); chatWin.focus(); }
  return reply;
}
ipcMain.on("stop-look", () => stopLooking("кнопка Стоп"));

// ---- перетаскивание: зажал и тащишь — Claude висит в воздухе; отпустил — падает на панель задач ----
let dragOff = { x: SIZE / 2, y: 30 }, fallTimer = null;
ipcMain.on("drag", (_e, phase, sx, sy) => {
  if (phase !== "move") log("drag", phase);
  if (!pet || pet.isDestroyed() || looking) return;
  if (phase === "start") {
    if (fallTimer) { clearInterval(fallTimer); fallTimer = null; }
    const b = pet.getBounds();
    dragOff = { x: sx - b.x, y: sy - b.y };
    yFree = b.y; act = "drag"; send({ act, left, project: lastProject });
  } else if (phase === "move" && yFree != null) {
    x = sx - dragOff.x; yFree = sy - dragOff.y; place();
  } else if (phase === "end" && yFree != null) {
    x = sx - dragOff.x; yFree = sy - dragOff.y; targetX = x;
    let v = 0;
    fallTimer = setInterval(() => {                       // падает с ускорением
      const floor = floorAt(x + SIZE / 2) - Math.round(SIZE * FEET);
      v += 2.4; yFree = Math.min(floor, yFree + v);
      if (yFree < floor - 400) yFree = floor - 400;       // уронили с самого верха — не ждём долго
      place();
      if (yFree >= floor) {
        clearInterval(fallTimer); fallTimer = null; yFree = null;
        act = "idle"; restTicks = 12; place(); send({ act, left, project: lastProject });
      }
    }, 16);
  }
});

// Клик по Claude с «!» (Claude Code закончил) — облачко с его последним сообщением, а не чат.
let codeNote = null;
function showCodeNote() {
  const project = lastProject || "Claude Code";
  const text = brain.lastCodeMessage() || "Задача готова — загляни в Claude Code.";
  brain.ackAlert();                                       // «!» снимаем — увидел
  codeNote = { id: "code-" + Date.now(), icon: "✅", app: "Claude Code", who: project,
    summary: text.length > 400 ? text.slice(0, 400) + "…" : text, label: "Глянь, что получилось", time: Date.now() };
  tick();
}
ipcMain.on("open-chat", () => {
  log("click: looking=" + !!looking, "activity=" + lastActivity);
  if (looking) stopLooking("клик по питомцу");
  else if (lastActivity === "alert") showCodeNote();
  else openChat();
});
ipcMain.on("menu", () => buildMenu().popup({ window: pet }));

// Автозапуск — ярлык в папке «Автозагрузка» с рабочей папкой питомца (как start-pet.cmd).
// Запись в реестре Run при входе в Windows не срабатывала, поэтому её больше не используем.
const STARTUP_LNK = join(app.getPath("appData"), "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "Claude Pet.lnk");
const autostartOn = () => existsSync(STARTUP_LNK);
function setAutostart(on) {
  if (on) shell.writeShortcutLink(STARTUP_LNK, "create", {
    target: process.execPath, args: ".", cwd: app.getAppPath(),
    icon: process.execPath, iconIndex: 0, description: "Мини-Claude",
  });
  else try { unlinkSync(STARTUP_LNK); } catch {}
  log("autostart", on);
}

function buildMenu() {
  return Menu.buildFromTemplate([
    { label: "Поговорить с Claude", click: openChat },
    { label: sleeping ? "Разбудить" : "Уложить спать", click: () => { sleeping = !sleeping; tick(); } },
    { label: "Запускать вместе с Windows", type: "checkbox", checked: autostartOn(), click: (m) => setAutostart(m.checked) },
    { type: "separator" },
    { label: "Выход", click: () => app.quit() },
  ]);
}

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  // переезд со старой записи в реестре на ярлык в «Автозагрузке»
  const oldRun = { path: process.execPath, args: [app.getAppPath()] };
  if (app.getLoginItemSettings(oldRun).openAtLogin || app.getLoginItemSettings().openAtLogin) {
    app.setLoginItemSettings({ ...oldRun, openAtLogin: false });
    app.setLoginItemSettings({ openAtLogin: false });
    setAutostart(true);
  }
  createPet();
  tray = new Tray(nativeImage.createFromPath(join(here, "icon.png")));
  tray.setToolTip("Claude");
  tray.on("click", openChat);
  tray.on("right-click", () => tray.popUpContextMenu(buildMenu()));
  setInterval(tick, 1000);
  // держимся поверх панели задач (она тоже «поверх всех»)
  setInterval(() => { if (pet && !pet.isDestroyed() && !fsHidden) pet.moveTop(); }, 4000);
  // Телефон по кабелю: держим проброс порта 8787 (он слетает при переподключении USB / перезапуске adb).
  ensureReverse(); setInterval(ensureReverse, 20_000);
  // самопроверка «посмотри на экран» (только при PET_SELFTEST=1): ответ — в pet.log
  if (process.env.PET_SELFTEST === "note") setTimeout(showCodeNote, 3000);
  else if (process.env.PET_SELFTEST) setTimeout(async () => log("selftest:", JSON.stringify(brain.screenStatus()?.log), await lookAtScreen(process.env.PET_SELFTEST === "control"
    ? { text: "Тест управления: нажми win, чтобы открыть меню Пуск, напечатай в поиске слово блокнот (инструментом type), сделай снимок и проверь, что текст появился в поле поиска, потом закрой меню клавишей esc. Enter не нажимай. Ответь одним предложением, появился ли текст.", history: [], control: true }
    : { text: "Опиши одним коротким предложением, что сейчас на экране.", history: [] })), 4000);
  screen.on("display-metrics-changed", () => place());
  screen.on("display-removed", () => { if (!onScreen(x + SIZE / 2)) { x = screen.getPrimaryDisplay().workArea.x + 100; place(); } });
});
app.on("window-all-closed", (e) => e.preventDefault());

// ---------- USB-связь с телефоном ----------
const ADB = [process.env.ADB, process.env.ANDROID_HOME && join(process.env.ANDROID_HOME, "platform-tools", "adb.exe"),
  join(homedir(), "AppData", "Local", "Android", "Sdk", "platform-tools", "adb.exe")].find((p) => p && existsSync(p)) || "adb";

function ensureReverse() {
  const opts = { windowsHide: true, timeout: 5000 };
  execFile(ADB, ["devices"], opts, (err, out) => {
    if (err) return;
    // проброс нужен только кабелю: беспроводные подключения (адрес:порт, _adb-tls) пропускаем —
    // по Wi-Fi телефон и так ходит на ПК через Tailscale
    const usb = out.split(/\r?\n/).map((l) => l.split(/\s+/))
      .filter(([serial, state]) => state === "device" && !serial.includes(":") && !serial.includes("_adb-tls"))
      .map(([serial]) => serial);
    for (const s of usb) {
      execFile(ADB, ["-s", s, "reverse", "--list"], opts, (e2, list) => {
        if (!e2 && list.includes("tcp:8787")) return;
        execFile(ADB, ["-s", s, "reverse", "tcp:8787", "tcp:8787"], opts, () => {});
      });
    }
  });
}
