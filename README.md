# Claude Phone Assistant + mini-Claude desktop pet

[Русская версия](README.ru.md)

A personal Android assistant that fights doomscrolling, plus a little blocky Claude that lives on your Windows taskbar. Both run on **your own Claude Code subscription**: the phone sends requests to the PC, and the PC answers through `claude -p`. No API key is needed.

> Unofficial hobby project. Not affiliated with or endorsed by Anthropic.

## What it does

**Phone (Android)**
- Counts Reels / Shorts / TikTok videos and blocks feeds after a daily limit. To get in, you explain why, and Claude decides whether to let you in for a few minutes.
- Reels opened from Stories are not counted.
- A morning summary notification and a 4×2 home-screen widget: videos watched, your next calendar event, and a "!" when Claude Code on the PC needs you.
- Chat with mini-Claude, or with real Claude Code sessions for your PC projects (one channel per project).
- Forwards important messenger and mail notifications to the PC pet. Ads and one-time codes are filtered out.
- English and Russian UI.

**PC (Windows)**
- mini-Claude walks along the taskbar, sits with a laptop while Claude Code is working, and waves with a "!" when a task is done. Click it to see Claude Code's last message.
- Click to chat. Drag it anywhere.
- 🖥️ button in the chat: Claude can look at your screen, or (red mode) click and type to do a task for you, with a Stop button and safety rules.
- Hosts the "brain" server for the phone on port 8787.

## Requirements

- Windows 10/11 PC with:
  - [Claude Code](https://claude.com/claude-code), installed and signed in (`claude` works in a terminal);
  - [Node.js](https://nodejs.org) 20+;
  - [Android Studio](https://developer.android.com/studio), only to build the app (it installs the SDK and Java).
- Android phone (Android 12+), with developer options and USB or wireless debugging on.
- [Tailscale](https://tailscale.com) on both, signed in to the same account, so the phone reaches the PC from anywhere. Without it, the phone works only over a USB cable.

## Setup

### 1. Start mini-Claude on the PC

```powershell
git clone https://github.com/gyzyr/claude-buddy.git
cd claude-buddy
.\pc\start-pet.cmd
```

The first run installs dependencies (Electron, nut.js) and creates `pc\.brain-token`, a random secret for phone requests. mini-Claude adds itself to Windows startup (you can turn that off from its tray menu).

### 2. Build and install the phone app

Connect the phone (cable, or `adb pair` / `adb connect` for wireless debugging), then:

```powershell
.\build-and-install.ps1
```

The script uses `ANDROID_HOME` and `JAVA_HOME` if set, otherwise the default Android Studio locations. It temporarily maps the project to drive `B:` because Gradle breaks on non-ASCII paths.

### 3. Connect the phone to the PC

```powershell
.\pc\connect-phone.ps1
```

This sends the PC's Tailscale address and token to the app. You can also enter them by hand in the app: **Settings → Mini-Claude on PC**, address `http://<PC Tailscale IP>:8787`, token from `pc\.brain-token`.

### 4. Grant permissions on the phone

In the app's **Settings → Permissions**, turn on:
- **Feed tracking** (accessibility service): counts videos and shows the barrier.
- **Usage access**.
- **Messages & mail → bubble on PC** (notification access).
- **Calendar**, for the next event in the summary and widget.

Also set the app's battery mode to **Unrestricted** in Android settings, so the system doesn't kill it.

## How it fits together

```
Phone app ──HTTP (Tailscale, token)──► mini-Claude (Electron, :8787) ──► claude -p (your subscription)
                                       └─ reads ~/.claude/projects to see what Claude Code is doing
```

- Brain requests run `claude -p` in an empty temp folder with a small system prompt and no MCP servers, to keep token use low.
- Requests from `127.0.0.1` (the PC itself, or the phone over `adb reverse`) need no token. Requests over Tailscale need the token.
- Screen control uses an MCP server (`pc/pet/screen-mcp.mjs`) that only mini-Claude starts. It is limited to 40 actions, and stops when you press Stop or click the pet.

## Notes

- The PC pet UI text is in Russian for now; the phone app has English and Russian.
- Windows only for the pet (taskbar placement, startup shortcut, `libnut-win32`).
- Smart App Control or antivirus may warn about unsigned tools. Don't disable them; this project doesn't need that.
- Claude Code usage counts against your plan limits. Feed decisions and notification checks use Haiku and are short.

## License

MIT
