// Connects the YouTube tab, the popup and the native program (ytdlna-desktop), which finds the
// renderers, serves the streams and drives the renderer.

const HOST = "uno.speranza.ytdlna";

const state = {
  renderers: [],
  renderer: null, // description URL of the chosen renderer
  rendererName: "",
  unsupported: 0, // renderers found that play none of our formats
  searching: false,
  tabId: null, // the tab whose video plays on the renderer
  casting: null, // {videoId, title, channel, duration, label, renderer}
  loading: null, // video id being sent
  status: null, // {state, position, duration}
  error: null,
  // Address of this computer for the renderer, if the automatic one does not reach it
  // (storage key "host"; for tests with an emulator: 10.0.2.2).
  host: "",
};

let port = null;

function native() {
  if (port) return port;
  port = browser.runtime.connectNative(HOST);
  port.onMessage.addListener(fromNative);
  port.onDisconnect.addListener((p) => {
    const message = p.error ? p.error.message : "Verbindung zu ytdlna-desktop beendet";
    port = null;
    if (state.tabId !== null) release(message);
    state.searching = false;
    state.error = message;
    changed();
  });
  return port;
}

function send(message) {
  try {
    native().postMessage(message);
  } catch (e) {
    state.error = `ytdlna-desktop nicht erreichbar: ${e.message}`;
    changed();
  }
}

function toTab(message) {
  if (state.tabId !== null) browser.tabs.sendMessage(state.tabId, message).catch(() => {});
}

const YOUTUBE = ["https://www.youtube.com/*", "https://music.youtube.com/*"];

// To the popup and to every YouTube tab (their cast buttons).
function changed() {
  browser.runtime.sendMessage({ type: "state", state }).catch(() => {});
  browser.tabs.query({ url: YOUTUBE }).then((tabs) => {
    for (const tab of tabs) {
      browser.tabs.sendMessage(tab.id, { type: "state", state, here: tab.id === state.tabId }).catch(() => {});
    }
  });
}

function fromNative(message) {
  switch (message.type) {
    case "renderers":
      // Renderers that play none of our formats are left out (and counted for the menus).
      state.renderers = message.renderers.filter((r) => !r.modes || r.modes.length);
      state.unsupported = message.renderers.length - state.renderers.length;
      // A chosen renderer stays listed while it does not answer (switched off, another network).
      if (state.renderer && !state.renderers.some((r) => r.url === state.renderer)) {
        state.renderers.push({ url: state.renderer, name: `${state.rendererName || state.renderer} (nicht gefunden)` });
      }
      state.searching = false;
      if (!state.renderer && state.renderers.length) {
        state.renderer = state.renderers[0].url;
      }
      break;
    case "casting":
      if (message.videoId !== state.loading) return;
      state.loading = null;
      state.casting = message;
      state.error = null;
      break;
    case "status":
      if (!state.casting || message.videoId !== state.casting.videoId) return;
      state.status = message;
      toTab({ type: "status", status: message });
      break;
    case "ended":
      // The tab plays the next title of its queue; its new video id then gets cast.
      toTab({ type: "next" });
      break;
    case "stopped":
      release(null);
      break;
    case "error":
      state.error = message.message;
      if (message.videoId && message.videoId === state.loading) state.loading = null;
      break;
  }
  changed();
}

function cast(tabId, videoId, start, music) {
  // Another tab cast so far: its player is free again.
  if (state.tabId !== null && state.tabId !== tabId) release(null);
  state.tabId = tabId;
  state.loading = videoId;
  state.status = null;
  state.error = null;
  // The native side picks the renderer's best format for a video or a song.
  send({ cmd: "cast", renderer: state.renderer, videoId, start: Math.floor(start || 0), mode: music ? "music" : "video",
         host: state.host });
  toTab({ type: "hold" });
  changed();
}

function select(url) {
  state.renderer = url;
  state.rendererName = (state.renderers.find((r) => r.url === url) || {}).name || "";
  browser.storage.local.set({ renderer: state.renderer, rendererName: state.rendererName });
}

function release(error) {
  toTab({ type: "release", position: state.status ? state.status.position : null });
  state.tabId = null;
  state.casting = null;
  state.loading = null;
  state.status = null;
  if (error) state.error = error;
}

// A content script in a YouTube tab (not the popup, which may be open as a tab too).
function fromPage(sender) {
  return !!sender.tab && !(sender.url || "").startsWith(browser.runtime.getURL(""));
}

browser.runtime.onMessage.addListener(async (message, sender) => {
  switch (message.type) {
    case "getState":
      return state;
    case "getTabState":
      return { state, here: !!sender.tab && sender.tab.id === state.tabId };
    case "discover":
      state.searching = true;
      send({ cmd: "discover" });
      changed();
      return;
    case "castHere":
      // The cast button in the page's player: this tab, on the device chosen in its menu.
      if (!fromPage(sender) || !message.videoId) return;
      select(message.url);
      cast(sender.tab.id, message.videoId, message.position, message.music);
      return;
    case "select":
      select(message.url);
      changed();
      return;
    case "castActiveTab": {
      // The active YouTube tab of this window, else of another one, else the last one used.
      const tabs = await browser.tabs.query({ url: YOUTUBE });
      const window = await browser.windows.getCurrent().catch(() => null);
      tabs.sort((a, b) => b.lastAccessed - a.lastAccessed);
      const tab = tabs.find((t) => t.active && window && t.windowId === window.id)
        || tabs.find((t) => t.active) || tabs[0];
      if (!state.renderer) return;
      if (!tab) {
        state.error = "Kein YouTube-Tab offen.";
        changed();
        return;
      }
      let video;
      try {
        video = await browser.tabs.sendMessage(tab.id, { type: "current" });
      } catch (e) {
        video = null;
      }
      if (!video || !video.videoId) {
        state.error = "In diesem Tab läuft kein YouTube-Video.";
        changed();
        return;
      }
      cast(tab.id, video.videoId, video.position, video.music);
      return;
    }
    case "control":
      // From the popup, or from the page's own buttons (the tab with the cast).
      if (fromPage(sender) && sender.tab.id !== state.tabId) return;
      if (message.cmd === "toggle") {
        const playing = state.status && state.status.state === "PLAYING";
        send({ cmd: playing ? "pause" : "play" });
      } else if (message.cmd === "stop") {
        send({ cmd: "stop" });
        release(null);
        changed();
      } else {
        send({ cmd: message.cmd, seconds: message.seconds });
      }
      return;
    case "video":
      // The cast tab moved on to another video (its queue, a click in the page).
      if (fromPage(sender) && sender.tab.id === state.tabId) cast(state.tabId, message.videoId, 0, message.music);
      return;
  }
});

browser.tabs.onRemoved.addListener((tabId) => {
  if (tabId !== state.tabId) return;
  send({ cmd: "stop" });
  state.tabId = null;
  release(null);
  changed();
});

browser.storage.onChanged.addListener((changes) => {
  if (changes.host) state.host = changes.host.newValue || "";
});

browser.storage.local.get(["renderer", "rendererName", "host"]).then((stored) => {
  if (stored.renderer && !state.renderer) {
    state.renderer = stored.renderer;
    state.rendererName = stored.rendererName || "";
  }
  if (stored.host) state.host = stored.host;
});
