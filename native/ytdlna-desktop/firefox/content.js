// In youtube.com and music.youtube.com: tells which video plays, holds the local player while the
// renderer plays, and turns the page's own controls into renderer commands.
//
// The local player stays muted and paused at the renderer's position, so the page's progress
// bar shows where the renderer is. Ads run muted to their end (the video element then holds the
// ad). Play and pause are taken from the clicks and keys that would toggle the local player.

const music = location.hostname === "music.youtube.com";

// After the extension was reloaded or updated, the script of the old version stays in open tabs,
// cut off from it: it must not swallow the page's clicks any more.
function alive() {
  try {
    return !!browser.runtime.id;
  } catch (e) {
    return false;
  }
}

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
    v.addEventListener("play", () => casting && alive() && hold());
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
  if (!casting || !alive() || adShowing() || !(event.target instanceof Element)) return;
  if (event.target.closest(PLAY_BUTTONS) || event.target.closest(VIDEO_AREA)) toggle(event);
}, true);

document.addEventListener("keydown", (event) => {
  if (!casting || !alive() || adShowing() || event.ctrlKey || event.metaKey || event.altKey) return;
  const target = event.target;
  if (target instanceof Element && target.closest("input, textarea, [contenteditable='true'], [contenteditable='']")) return;
  if (event.key === " " || event.key === "k" || event.key === "K") toggle(event);
}, true);

function newVideo(id) {
  castId = id;
  tvPosition = 0;
  browser.runtime.sendMessage({ type: "video", videoId: id, music, queue: readQueue(), autoplay: isAutoplay() });
}

// The tab's queue, for the renderer's playlist. Read from the page's own data (wrappedJSObject:
// the page's objects, not their DOM).

function runText(value) {
  if (!value) return "";
  if (typeof value.simpleText === "string") return value.simpleText;
  let text = "";
  const runs = value.runs;
  if (runs) for (let i = 0; i < runs.length; i++) text += runs[i].text;
  return text;
}

function lastThumbnail(value) {
  const list = value && value.thumbnails;
  return list && list.length ? String(list[list.length - 1].url).split("=w")[0] : "";
}

function clockSeconds(text) {
  return String(text || "").split(":").reduce((total, part) => total * 60 + (parseInt(part, 10) || 0), 0);
}

function musicQueue() {
  const queue = document.querySelector("ytmusic-player-queue");
  if (!queue) return [];
  const tracks = [];
  for (const element of queue.querySelectorAll("ytmusic-player-queue-item")) {
    const data = element.wrappedJSObject && element.wrappedJSObject.data;
    if (!data || !data.videoId) continue;
    // "Artist • Album • Year" for songs, "Artist • 1.2M views" for videos.
    const byline = runText(data.longBylineText).split(" • ");
    const year = byline.length >= 3 && /^\d{4}$/.test(byline[byline.length - 1]) ? byline[byline.length - 1] : "";
    tracks.push({
      videoId: String(data.videoId),
      title: runText(data.title),
      artist: runText(data.shortBylineText) || byline[0] || "",
      album: year ? byline.slice(1, -1).join(" • ") : "",
      year,
      art: lastThumbnail(data.thumbnail),
      duration: clockSeconds(runText(data.lengthText)),
    });
  }
  return tracks;
}

function youtubeQueue() {
  const p = api();
  if (!p) return [];
  const titles = {};
  for (const element of document.querySelectorAll("ytd-playlist-panel-video-renderer")) {
    const data = element.wrappedJSObject && element.wrappedJSObject.data;
    if (data && data.videoId) {
      titles[data.videoId] = { title: runText(data.title), artist: runText(data.shortBylineText),
                               duration: clockSeconds(runText(data.lengthText)) };
    }
  }
  const playlist = p.getPlaylist && p.getPlaylist();
  if (playlist && playlist.length) {
    const tracks = [];
    for (let i = 0; i < playlist.length; i++) {
      const id = String(playlist[i]);
      tracks.push(Object.assign({ videoId: id }, titles[id] || {}));
    }
    return tracks;
  }
  // A single video: it and what autoplay plays next.
  const data = p.getVideoData && p.getVideoData();
  const id = videoId();
  if (!id) return [];
  const tracks = [{ videoId: id, title: data ? String(data.title || "") : "", artist: data ? String(data.author || "") : "" }];
  const toggle = document.querySelector(".ytp-autonav-toggle-button");
  if (!toggle || toggle.getAttribute("aria-checked") === "true") {
    try {
      const response = p.getWatchNextResponse();
      const next = response.contents.twoColumnWatchNextResults.autoplay.autoplay.sets[0].autoplayVideo.watchEndpoint.videoId;
      if (next && next !== id) tracks.push({ videoId: String(next) });
    } catch (e) {
      // No autoplay video.
    }
  }
  return tracks;
}

// A single YouTube video: its queue is just it and what autoplay plays next.
function isAutoplay() {
  if (music) return false;
  const p = api();
  const playlist = p && p.getPlaylist && p.getPlaylist();
  return !(playlist && playlist.length);
}

function readQueue() {
  try {
    return music ? musicQueue() : youtubeQueue();
  } catch (e) {
    return [];
  }
}

let sentQueue = "";

function sendQueue() {
  const queue = readQueue();
  const key = queue.map((t) => t.videoId).join(",");
  if (!queue.length || key === sentQueue) return;
  sentQueue = key;
  browser.runtime.sendMessage({ type: "queue", queue, autoplay: isAutoplay() });
}

// The renderer moved on to another title: the tab follows, its player held paused.
function goTo(id) {
  if (id === videoId()) return;
  castId = id;
  tvPosition = 0;
  if (music) {
    for (const element of document.querySelectorAll("ytmusic-player-queue ytmusic-player-queue-item")) {
      const data = element.wrappedJSObject && element.wrappedJSObject.data;
      if (data && data.videoId === id) {
        const play = element.querySelector("ytmusic-play-button-renderer, .song-info");
        (play || element).click();
        return;
      }
    }
  } else {
    const p = api();
    const playlist = p && p.getPlaylist && p.getPlaylist();
    if (playlist && playlist.length) {
      for (let i = 0; i < playlist.length; i++) {
        if (String(playlist[i]) === id) return p.playVideoAt(i);
      }
    }
    const queue = youtubeQueue();
    if (p && queue.length > 1 && queue[1].videoId === id) return p.nextVideo();
  }
  location.assign(`${location.origin}/watch?v=${id}`);
}

function next() {
  const button = document.querySelector(music ? "ytmusic-player-bar .next-button" : ".ytp-next-button");
  if (button) button.click();
}

browser.runtime.onMessage.addListener((message) => {
  switch (message.type) {
    case "current":
      return Promise.resolve({ videoId: videoId(), position: adShowing() ? 0 : localTime(), music, queue: readQueue(),
                               autoplay: isAutoplay() });
    case "goto":
      if (casting) goTo(message.videoId);
      return;
    case "hold": {
      const v = element();
      wasMuted = v ? v.muted : false;
      casting = true;
      castId = videoId();
      sentQueue = "";
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
const ticker = setInterval(() => {
  if (!alive()) {
    // The old version's script: the page is the page's again.
    casting = false;
    if (video) video.muted = wasMuted;
    return clearInterval(ticker);
  }
  if (!casting) return;
  hold();
  const id = videoId();
  if (id && id !== castId) newVideo(id);
  else sendQueue();
}, 1000);
