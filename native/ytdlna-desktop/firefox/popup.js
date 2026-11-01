const $ = (id) => document.getElementById(id);

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
  }
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
$("stop").onclick = () => browser.runtime.sendMessage({ type: "control", cmd: "stop" });

browser.runtime.sendMessage({ type: "getState" }).then((state) => {
  render(state);
  if (!state.renderers.length && !state.searching) browser.runtime.sendMessage({ type: "discover" });
});
