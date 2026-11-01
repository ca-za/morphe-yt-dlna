// In youtube.com and music.youtube.com: tells which video plays, holds the local player while the
// renderer plays, and turns the page's own controls into renderer commands.
//
// The local player stays muted and paused at the renderer's position, so the page's progress
// bar shows where the renderer is. Ads run muted to their end (the video element then holds the
// ad). Play and pause are taken from the clicks and keys that would toggle the local player.

const music = location.hostname === "music.youtube.com";

let casting = false;
let castId = null;
let tvPosition = 0;
let wasMuted = false;
// Where our own sync moved the local player: its "seeked" is not the user's.
let syncTarget = null;
// When the user last seeked: the renderer needs a moment to report the new position.
let userSeekAt = 0;
let video = null;

function videoId() {
  const id = new URL(location.href).searchParams.get("v");
  return id && /^[\w-]{11}$/.test(id) ? id : null;
}

function element() {
  return document.querySelector("#movie_player video, video");
}

// YouTube's player object (the same in YouTube Music), from the page's side.
function api() {
  const p = document.getElementById("movie_player");
  return p && p.wrappedJSObject && typeof p.wrappedJSObject.seekTo === "function" ? p.wrappedJSObject : null;
}

function adShowing() {
  return !!document.querySelector("#movie_player.ad-showing");
}

function localTime() {
  const p = api();
  return p ? p.getCurrentTime() : video ? video.currentTime : 0;
}

function seekLocal(seconds) {
  const p = api();
  if (p) p.seekTo(seconds, true);
  else if (video) video.currentTime = seconds;
}

function hold() {
  // For tests and bug reports: what this script sees, on the page's root element.
  document.documentElement.dataset.ytdlna = JSON.stringify({ casting, castId, tvPosition, ad: adShowing(), api: !!api() });
  const v = element();
  if (!v) return;
  if (v !== video) {
    video = v;
    v.addEventListener("seeked", onSeeked);
    v.addEventListener("play", () => casting && hold());
  }
  v.muted = true;
  if (adShowing()) {
    // Played muted to its end, then the video itself loads and is held.
    if (v.paused) v.play().catch(() => {});
    return;
  }
  if (!v.paused) {
    const p = api();
    if (p) p.pauseVideo();
    else v.pause();
  }
}

function onSeeked() {
  if (!casting || adShowing()) return;
  const now = localTime();
  if (syncTarget !== null && Math.abs(now - syncTarget) < 1.5) {
    syncTarget = null;
    return;
  }
  syncTarget = null;
  if (Math.abs(now - tvPosition) < 2) return;
  tvPosition = now;
  userSeekAt = Date.now();
  browser.runtime.sendMessage({ type: "control", cmd: "seek", seconds: Math.floor(now) });
}

function toggle(event) {
  event.preventDefault();
  event.stopImmediatePropagation();
  browser.runtime.sendMessage({ type: "control", cmd: "toggle" });
}

const PLAY_BUTTONS = music ? "#play-pause-button" : ".ytp-play-button";
// A click on the video picture toggles playback in YouTube too.
const VIDEO_AREA = music ? "#song-video, #movie_player .html5-video-container" : "#movie_player .html5-video-container";

document.addEventListener("click", (event) => {
  if (!casting || adShowing() || !(event.target instanceof Element)) return;
  if (event.target.closest(PLAY_BUTTONS) || event.target.closest(VIDEO_AREA)) toggle(event);
}, true);

document.addEventListener("keydown", (event) => {
  if (!casting || adShowing() || event.ctrlKey || event.metaKey || event.altKey) return;
  const target = event.target;
  if (target instanceof Element && target.closest("input, textarea, [contenteditable='true'], [contenteditable='']")) return;
  if (event.key === " " || event.key === "k" || event.key === "K") toggle(event);
}, true);

function newVideo(id) {
  castId = id;
  tvPosition = 0;
  browser.runtime.sendMessage({ type: "video", videoId: id, music });
}

function next() {
  const button = document.querySelector(music ? "ytmusic-player-bar .next-button" : ".ytp-next-button");
  if (button) button.click();
}

browser.runtime.onMessage.addListener((message) => {
  switch (message.type) {
    case "current":
      return Promise.resolve({ videoId: videoId(), position: adShowing() ? 0 : localTime(), music });
    case "hold": {
      const v = element();
      wasMuted = v ? v.muted : false;
      casting = true;
      castId = videoId();
      hold();
      return;
    }
    case "release":
      casting = false;
      castId = null;
      if (video) video.muted = wasMuted;
      if (message.position !== null && message.position !== undefined && !adShowing()) seekLocal(message.position);
      return;
    case "status": {
      if (!casting) return;
      hold();
      if (adShowing()) return;
      // Right after a seek in the page the renderer may still report the old position.
      if (Date.now() - userSeekAt < 4000 || (video && video.seeking)) return;
      tvPosition = message.status.position;
      // The page's progress bar follows the renderer.
      if (Math.abs(localTime() - tvPosition) > 3) {
        syncTarget = tvPosition;
        seekLocal(tvPosition);
      }
      return;
    }
    case "next":
      next();
      return;
  }
});

// YouTube is a single page app: a click on another video changes the URL, not the page.
setInterval(() => {
  if (!casting) return;
  hold();
  const id = videoId();
  if (id && id !== castId) newVideo(id);
}, 1000);
