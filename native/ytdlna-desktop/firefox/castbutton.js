// The cast button in the player (YouTube: next to the subtitles button; YouTube Music: in the
// player bar before repeat) and its device menu. Uses videoId(), localTime(), adShowing() and
// music from content.js.

const CAST_ICON_PATHS =
  '<path d="M21 3H3a2 2 0 0 0-2 2v3h2V5h18v14h-7v2h7a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2z"/>' +
  '<path d="M1 18v3h3a3 3 0 0 0-3-3zm0-4v2a5 5 0 0 1 5 5h2a7 7 0 0 0-7-7zm0-4v2a9 9 0 0 1 9 9h2A11 11 0 0 0 1 10z"/>';
const CAST_ICON = `<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true">${CAST_ICON_PATHS}</svg>`;

let ext = { state: null, here: false };
let button = null;
let menu = null;

function makeButton() {
  const b = document.createElement("button");
  b.className = music ? "ytdlna-cast" : "ytp-button ytdlna-cast";
  b.title = "Auf DLNA-Gerät abspielen";
  b.setAttribute("aria-label", b.title);
  b.setAttribute("aria-haspopup", "menu");
  b.innerHTML = CAST_ICON;
  const style = music
    ? "width:40px;height:40px;padding:8px;margin:0 4px;border:0;background:none;cursor:pointer;color:rgb(144,144,144);display:inline-flex;align-items:center;justify-content:center;"
    : "display:inline-flex;align-items:center;justify-content:center;color:#fff;";
  b.setAttribute("style", style);
  b.addEventListener("click", (event) => {
    event.preventDefault();
    event.stopPropagation();
    if (menu) closeMenu();
    else openMenu();
  });
  return b;
}

// YouTube re-renders its controls now and then: put the button back when it is gone.
function placeButton() {
  if (button && button.isConnected) return;
  const anchor = music
    ? document.querySelector("ytmusic-player-bar .right-controls-buttons .repeat")
    : document.querySelector(".ytp-right-controls-left .ytp-subtitles-button");
  if (!anchor) return;
  button = makeButton();
  anchor.parentNode.insertBefore(button, anchor);
  paintButton();
}

function paintButton() {
  if (!button) return;
  const active = ext.here && ext.state && (ext.state.casting || ext.state.loading);
  button.style.color = active ? "#e53935" : music ? "rgb(144,144,144)" : "#fff";
  button.title = active && ext.state.casting
    ? `Läuft auf ${ext.state.casting.renderer}` : "Auf DLNA-Gerät abspielen";
}

const MENU_STYLE = `
  :host { all: initial; }
  .menu { position: fixed; z-index: 2147483647; min-width: 240px; max-width: 320px; padding: 8px 0;
          background: rgba(28, 28, 28, .96); color: #fff; border-radius: 12px;
          font: 14px/1.4 Roboto, Arial, sans-serif; box-shadow: 0 4px 24px rgba(0, 0, 0, .5); }
  .head { padding: 6px 16px 8px; color: #aaa; font-size: 12px; }
  button { all: unset; box-sizing: border-box; display: flex; align-items: center; gap: 12px; width: 100%;
           padding: 8px 16px; cursor: pointer; }
  button:hover, button:focus-visible { background: rgba(255, 255, 255, .1); }
  .check { width: 16px; color: #e53935; }
  .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .muted { color: #aaa; padding: 8px 16px; }
  .error { color: #ff8a80; padding: 8px 16px; }
  hr { border: 0; border-top: 1px solid rgba(255, 255, 255, .15); margin: 6px 0; }
`;

function openMenu() {
  const host = document.createElement("div");
  host.id = "ytdlna-menu";
  const root = host.attachShadow({ mode: "open" });
  root.innerHTML = `<style>${MENU_STYLE}</style><div class="menu" role="menu"></div>`;
  menu = { host, box: root.querySelector(".menu") };
  document.body.appendChild(host);
  renderMenu();
  browser.runtime.sendMessage({ type: "getTabState" }).then((s) => {
    ext = s;
    renderMenu();
    if (!s.state.renderers.length && !s.state.searching) browser.runtime.sendMessage({ type: "discover" });
  });
  setTimeout(() => document.addEventListener("click", outside, true), 0);
  document.addEventListener("keydown", escape, true);
}

function outside(event) {
  if (menu && !event.composedPath().includes(menu.host) && event.target !== button && !button.contains(event.target)) closeMenu();
}

function escape(event) {
  if (event.key === "Escape") closeMenu();
}

function closeMenu() {
  if (!menu) return;
  menu.host.remove();
  menu = null;
  document.removeEventListener("click", outside, true);
  document.removeEventListener("keydown", escape, true);
}

function item(label, onClick, checked) {
  const b = document.createElement("button");
  b.setAttribute("role", "menuitem");
  const check = document.createElement("span");
  check.className = "check";
  check.textContent = checked ? "✓" : "";
  const name = document.createElement("span");
  name.className = "name";
  name.textContent = label;
  b.append(check, name);
  b.addEventListener("click", (event) => {
    event.stopPropagation();
    onClick();
  });
  return b;
}

function line(className, text) {
  const div = document.createElement("div");
  div.className = className;
  div.textContent = text;
  return div;
}

function renderMenu() {
  if (!menu) return;
  const box = menu.box;
  box.replaceChildren();
  const s = ext.state;
  const castingHere = ext.here && s && s.casting;
  box.append(line("head", castingHere ? `Läuft auf ${s.casting.renderer}` : "Abspielen auf"));
  if (!s) {
    box.append(line("muted", "…"));
  } else {
    for (const r of s.renderers) {
      const current = castingHere && r.url === s.renderer;
      box.append(item(r.name, () => castOn(r.url), current));
    }
    if (!s.renderers.length) box.append(line("muted", s.searching ? "Suche Geräte …" : "Keine Geräte gefunden"));
    if (s.unsupported) {
      box.append(line("muted", s.unsupported === 1 ? "1 Gerät ohne passendes Format ausgeblendet"
        : `${s.unsupported} Geräte ohne passendes Format ausgeblendet`));
    }
    box.append(document.createElement("hr"));
    if (castingHere) {
      box.append(item("Wiedergabe beenden", () => {
        browser.runtime.sendMessage({ type: "control", cmd: "stop" });
        closeMenu();
      }));
    }
    if (!s.searching) box.append(item("Erneut suchen", () => browser.runtime.sendMessage({ type: "discover" })));
    if (s.error) box.append(line("error", s.error));
  }
  // Above the button, kept inside the window.
  const r = button.getBoundingClientRect();
  const width = box.offsetWidth || 260;
  box.style.left = `${Math.max(8, Math.min(r.right - width, window.innerWidth - width - 8))}px`;
  box.style.bottom = `${Math.max(8, window.innerHeight - r.top + 8)}px`;
}

function castOn(url) {
  const id = videoId();
  if (!id) return;
  browser.runtime.sendMessage({ type: "castHere", url, videoId: id, position: adShowing() ? 0 : localTime(), music,
                                queue: readQueue(), autoplay: isAutoplay() });
  closeMenu();
}

browser.runtime.onMessage.addListener((message) => {
  if (message.type !== "state") return;
  ext = { state: message.state, here: message.here };
  paintButton();
  renderMenu();
});

setInterval(placeButton, 1000);
placeButton();
