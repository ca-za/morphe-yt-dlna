const $ = (id) => document.getElementById(id);
let volumeMovedAt = 0;
let volumeTimer = null;

function time(seconds) {
  seconds = Math.floor(seconds || 0);
  const m = Math.floor(seconds / 60), s = String(seconds % 60).padStart(2, "0");
  return m >= 60 ? `${Math.floor(m / 60)}:${String(m % 60).padStart(2, "0")}:${s}` : `${m}:${s}`;
}

function render(state) {
  const select = $("renderer");
  select.replaceChildren(...state.renderers.map((r) => new Option(r.name, r.url, false, r.url === state.renderer)));
  if (!state.renderers.length) select.append(new Option(state.searching ? "Suche ..." : "Keine Geräte gefunden", ""));
  $("discover").disabled = state.searching;
  $("hidden").hidden = !state.unsupported;
  $("hidden").textContent = state.unsupported === 1 ? "1 Gerät ohne passendes Format ausgeblendet"
    : `${state.unsupported} Geräte ohne passendes Format ausgeblendet`;
  $("cast").disabled = !state.renderer || !!state.loading;
  $("cast").textContent = state.loading ? "Wird gesendet ..." : "Diesen Tab abspielen";

  const c = state.casting;
  $("now").hidden = !c;
  if (c) {
    $("title").textContent = c.title;
    $("meta").textContent = `${c.channel} · ${c.renderer} · ${c.label}`;
    const s = state.status;
    $("time").textContent = s ? `${time(s.position)} / ${time(c.duration)}` : "";
    $("toggle").textContent = s && s.state === "PLAYING" ? "Pause" : "Abspielen";
    // Not while the slider is moved: the renderer's answer lags behind.
    // A value (slider), only steps (a receiver: − + mute, no value to show) or nothing.
    const control = s ? s.volumeControl : null;
    const volume = s && typeof s.volume === "number" ? s.volume : null;
    $("volume-absolute").hidden = control !== "absolute";
    $("volume-steps").hidden = control !== "steps";
    $("steps-label").textContent = s && s.volumeTarget === "hdmi-cec" ? "Lautstärke am Receiver" : "Lautstärke";
    $("volume").disabled = volume === null;
    if (volume !== null && Date.now() - volumeMovedAt > 2500) $("volume").value = volume;
  }
  renderRooms(state);
  $("error").hidden = !state.error;
  $("error").textContent = state.error || "";
}

browser.runtime.onMessage.addListener((m) => {
  if (m.type === "state") render(m.state);
});
$("discover").onclick = () => browser.runtime.sendMessage({ type: "discover" });
$("renderer").onchange = (e) => browser.runtime.sendMessage({ type: "select", url: e.target.value });
$("cast").onclick = () => browser.runtime.sendMessage({ type: "castActiveTab" });
$("toggle").onclick = () => browser.runtime.sendMessage({ type: "control", cmd: "toggle" });
$("volume").oninput = (e) => {
  volumeMovedAt = Date.now();
  clearTimeout(volumeTimer);
  volumeTimer = setTimeout(() => browser.runtime.sendMessage({ type: "control", cmd: "volume", value: Number(e.target.value) }), 120);
};
$("up").onclick = () => browser.runtime.sendMessage({ type: "control", cmd: "volumeStep", steps: 1 });
$("down").onclick = () => browser.runtime.sendMessage({ type: "control", cmd: "volumeStep", steps: -1 });
$("mute").onclick = () => browser.runtime.sendMessage({ type: "control", cmd: "toggleMute" });
$("stop").onclick = () => browser.runtime.sendMessage({ type: "control", cmd: "stop" });

browser.runtime.sendMessage({ type: "getState" }).then((state) => {
  render(state);
  if (!state.renderers.length && !state.searching) browser.runtime.sendMessage({ type: "discover" });
});

// Sonos: the rooms that play along with the group, each with its volume.
let roomsAsked = false;
let roomMovedAt = 0;

function renderRooms(state) {
  const sonos = state.casting && (state.renderers.find((r) => r.url === state.renderer) || {}).sonos;
  $("rooms").hidden = !sonos;
  if (!sonos) return;
  if (!roomsAsked) {
    roomsAsked = true;
    browser.runtime.sendMessage({ type: "rooms", cmd: "rooms" });
  }
  if (Date.now() - roomMovedAt < 2500) return;
  const rooms = state.rooms && state.rooms.renderer === state.renderer ? state.rooms.rooms : [];
  $("room-list").replaceChildren(...rooms.map((room) => {
    const row = document.createElement("label");
    row.className = "room";
    const box = document.createElement("input");
    box.type = "checkbox";
    box.checked = room.in;
    box.disabled = room.leading;
    box.onchange = () => browser.runtime.sendMessage({ type: "rooms", cmd: "room", id: room.id, in: box.checked });
    const name = document.createElement("span");
    name.textContent = room.leading ? `${room.name} (führt)` : room.name;
    row.append(box, name);
    if (room.in && typeof room.volume === "number") {
      const slider = document.createElement("input");
      slider.type = "range";
      slider.min = "0";
      slider.max = "100";
      slider.value = room.volume;
      let timer = null;
      slider.oninput = () => {
        roomMovedAt = Date.now();
        clearTimeout(timer);
        timer = setTimeout(() => browser.runtime.sendMessage({ type: "rooms", cmd: "roomVolume", id: room.id,
                                                               value: Number(slider.value) }), 150);
      };
      row.append(slider);
    }
    return row;
  }));
}
