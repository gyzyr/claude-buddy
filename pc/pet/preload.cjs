const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("pet", {
  onState: (cb) => ipcRenderer.on("state", (_e, s) => cb(s)),
  openChat: () => ipcRenderer.send("open-chat"),
  menu: () => ipcRenderer.send("menu"),
  drag: (phase, x, y) => ipcRenderer.send("drag", phase, x, y),
  chat: (messages) => ipcRenderer.invoke("chat", messages),
  // «посмотри на экран»: ответ приходит, когда Claude досмотрит
  screen: (text, history, control) => ipcRenderer.invoke("screen", { text, history, control }),
  stopLook: () => ipcRenderer.send("stop-look"),
  onStatus: (cb) => ipcRenderer.on("status", (_e, t) => cb(t)),
  // облачко с важным сообщением с телефона
  onPopup: (cb) => ipcRenderer.on("popup", (_e, p) => cb(p)),
  dismissPopup: (id) => ipcRenderer.send("popup-dismiss", id),
  popupSize: (h) => ipcRenderer.send("popup-size", h),
});
