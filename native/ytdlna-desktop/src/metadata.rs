//! What the renderer shows about a title: name, artist, album, year, label and cover.
//!
//! The page gives what it knows (YouTube Music: title, artist, often album and year); oEmbed
//! fills in titles the page did not give. For songs, Deezer (then iTunes) finds the album cover
//! in high resolution, the album, year and label, as the Android app does (AlbumArt.java).

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use regex::Regex;
use serde_json::Value;
use unicode_normalization::UnicodeNormalization;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Track {
    pub video_id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub year: String,
    pub label: String,
    /// Cover or thumbnail URL.
    pub art: String,
    /// Seconds, 0 if unknown.
    pub duration: u64,
}

impl Track {
    pub fn from_json(v: &Value) -> Track {
        let text = |key: &str| v[key].as_str().unwrap_or("").trim().to_string();
        Track {
            video_id: text("videoId"),
            title: text("title"),
            artist: text("artist"),
            album: text("album"),
            year: text("year"),
            label: String::new(),
            art: text("art"),
            duration: v["duration"].as_u64().unwrap_or(0),
        }
    }
}

/// Fills in what is missing. `music`: a song, worth a music database lookup.
pub fn complete(track: &mut Track, music: bool) {
    if track.title.is_empty() || track.artist.is_empty() {
        if let Some((title, author, thumbnail)) = oembed(&track.video_id) {
            if track.title.is_empty() {
                track.title = title;
            }
            if track.artist.is_empty() {
                track.artist = author;
            }
            if track.art.is_empty() {
                track.art = thumbnail;
            }
        }
    }
    if track.art.is_empty() {
        track.art = format!("https://i.ytimg.com/vi/{}/hqdefault.jpg", track.video_id);
    }
    if !music || track.title.is_empty() {
        return;
    }
    let artist = artist_name(&track.artist);
    let Some(found) = lookup(&track.title, &artist) else { return };
    if !found.is_sure() {
        return;
    }
    // The same song: its album cover, album and year are right for it.
    track.art = found.cover.clone();
    if track.album.is_empty() {
        track.album = found.album.clone();
    }
    let (year, label) = details(&found);
    if track.year.is_empty() {
        track.year = year;
    }
    track.label = label;
    if track.duration == 0 {
        track.duration = found.duration;
    }
}

/// Channel names of artists: "Rick Astley - Topic", "RickAstleyVEVO".
fn artist_name(channel: &str) -> String {
    let name = channel.trim_end_matches(" - Topic");
    let name = name.strip_suffix("VEVO").unwrap_or(name);
    name.trim().to_string()
}

fn agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| ureq::AgentBuilder::new().timeout(Duration::from_secs(4)).build())
}

fn get_json(url: &str) -> Option<Value> {
    let body = agent().get(url).set("Accept", "application/json").call().ok()?.into_string().ok()?;
    serde_json::from_str(&body).ok()
}

/// Title, channel and thumbnail of a video, without YouTube's API.
fn oembed(video_id: &str) -> Option<(String, String, String)> {
    static CACHE: OnceLock<Mutex<HashMap<String, Option<(String, String, String)>>>> = OnceLock::new();
    let cache = CACHE.get_or_init(Default::default);
    if let Some(known) = cache.lock().unwrap_or_else(|e| e.into_inner()).get(video_id) {
        return known.clone();
    }
    let found = get_json(&format!(
        "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D{video_id}"
    ))
    .map(|v| {
        let text = |key: &str| v[key].as_str().unwrap_or("").to_string();
        (text("title"), text("author_name"), text("thumbnail_url"))
    });
    cache.lock().unwrap_or_else(|e| e.into_inner()).insert(video_id.to_string(), found.clone());
    found
}

// Music database lookup (Deezer, then iTunes), as AlbumArt.java

/// The score from which a result is the same song.
const GOOD_MATCH: i32 = 2;

#[derive(Clone, Debug)]
pub struct Match {
    pub cover: String,
    pub album: String,
    pub year: String,
    pub duration: u64,
    pub deezer_album: String,
    pub score: i32,
}

impl Match {
    pub fn is_sure(&self) -> bool {
        self.score >= GOOD_MATCH
    }
}

fn noise() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"(?i)\s*[(\[][^)\]]*(official|video|audio|lyric|visuali[sz]er|remaster|hd|4k|mv)[^)\]]*[)\]]").unwrap())
}

fn featuring() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"(?i)\s+(feat\.?|ft\.?|featuring)\s+.*$").unwrap())
}

fn compilation() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(
        r"(?i)\b(hits|best of|greatest|collection|decades?|anthology|essential|now that|compilation|various|party|summer|verano|top \d+|\d0'?s|\d0er|classics?|original \d0|playlist|karaoke|tribute|workout)\b",
    ).unwrap())
}

/// The song title of a video title: no "(Official Video)", no "feat. ...", no "Artist - " in front.
pub fn clean_title(title: &str, artist: &str) -> String {
    let mut cleaned = noise().replace_all(title, "").to_string();
    cleaned = featuring().replace_all(&cleaned, "").to_string();
    if let Some((left, right)) = cleaned.split_once(" - ") {
        if !artist.is_empty() && similar(left, artist) {
            cleaned = right.to_string();
        }
    }
    cleaned.trim().to_string()
}

fn normalize(s: &str) -> String {
    s.nfd().filter(|c| !unicode_normalization::char::is_combining_mark(*c))
        .flat_map(char::to_lowercase).filter(|c| c.is_ascii_alphanumeric()).collect()
}

/// Artist names match loosely: "Rick Astley" ~ "rick astley", "Bronski Beat" ~ "Bronski Beat & Marc Almond".
fn similar(a: &str, b: &str) -> bool {
    let (x, y) = (normalize(a), normalize(b));
    !x.is_empty() && !y.is_empty() && (x.contains(&y) || y.contains(&x))
}

/// Rates a search result: the same title scores, compilations and karaoke versions do not.
fn score(title: &str, found_title: &str, album: &str, various_artists: bool) -> i32 {
    let (t, f) = (normalize(title), normalize(found_title));
    let mut score = 0;
    if t == f {
        score += 2;
    } else if f.starts_with(&t) || t.starts_with(&f) {
        score += 1;
    }
    if compilation().is_match(album) || various_artists {
        score -= 3;
    }
    if normalize(album) == t {
        score += 1; // The single.
    }
    score
}

fn encode(s: &str) -> String {
    s.bytes().map(|b| match b {
        b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
        _ => format!("%{b:02X}"),
    }).collect()
}

pub fn lookup(title: &str, artist: &str) -> Option<Match> {
    static CACHE: OnceLock<Mutex<HashMap<String, Option<Match>>>> = OnceLock::new();
    let cache = CACHE.get_or_init(Default::default);
    let title = clean_title(title, artist);
    let key = format!("{}|{}", normalize(&title), normalize(artist));
    if let Some(known) = cache.lock().unwrap_or_else(|e| e.into_inner()).get(&key) {
        return known.clone();
    }
    let deezer = deezer(&title, artist);
    let mut best = deezer.clone();
    if !deezer.as_ref().is_some_and(Match::is_sure) {
        if let Some(itunes) = itunes(&title, artist) {
            if best.as_ref().map_or(true, |b| !b.is_sure() && itunes.score > b.score) {
                best = Some(itunes);
            }
        }
    }
    eprintln!("ytdlna host: cover for {title:?} / {artist:?}: {}",
              best.as_ref().map_or("none".to_string(), |b| format!("{:?} {} (score {})", b.album, b.year, b.score)));
    cache.lock().unwrap_or_else(|e| e.into_inner()).insert(key, best.clone());
    best
}

/// Deezer search: https://developers.deezer.com/api/search, 1000x1000 covers.
fn deezer(title: &str, artist: &str) -> Option<Match> {
    let query = if artist.is_empty() { title.to_string() } else { format!("artist:\"{artist}\" track:\"{title}\"") };
    let mut data = get_json(&format!("https://api.deezer.com/search?limit=10&q={}", encode(&query)))?["data"].clone();
    if data.as_array().map_or(true, Vec::is_empty) && !artist.is_empty() {
        // The strict search found nothing: a plain one.
        data = get_json(&format!("https://api.deezer.com/search?limit=10&q={}", encode(&format!("{artist} {title}"))))?["data"].clone();
    }
    let mut best: Option<Match> = None;
    for track in data.as_array()? {
        let track_artist = track["artist"]["name"].as_str().unwrap_or("");
        if !artist.is_empty() && !similar(artist, track_artist) {
            continue;
        }
        let album = &track["album"];
        let cover = album["cover_xl"].as_str().unwrap_or("");
        if !cover.starts_with("http") {
            continue;
        }
        let album_title = album["title"].as_str().unwrap_or("");
        let score = score(title, track["title"].as_str().unwrap_or(""), album_title, false);
        if best.as_ref().map_or(true, |b| score > b.score) {
            best = Some(Match {
                cover: cover.to_string(),
                album: album_title.to_string(),
                year: String::new(),
                duration: track["duration"].as_u64().unwrap_or(0),
                deezer_album: album["id"].as_u64().map(|id| id.to_string()).unwrap_or_default(),
                score,
            });
        }
    }
    best
}

/// iTunes Search API, covers up to 3000x3000.
fn itunes(title: &str, artist: &str) -> Option<Match> {
    let term = format!("{artist} {title}");
    let results = get_json(&format!("https://itunes.apple.com/search?media=music&entity=song&limit=10&term={}",
                                    encode(term.trim())))?["results"].clone();
    let mut best: Option<Match> = None;
    for song in results.as_array()? {
        let text = |key: &str| song[key].as_str().unwrap_or("").to_string();
        if !artist.is_empty() && !similar(artist, &text("artistName")) {
            continue;
        }
        let cover = text("artworkUrl100");
        if !cover.starts_with("http") {
            continue;
        }
        let collection_artist = text("collectionArtistName");
        let various = !collection_artist.is_empty() && !similar(artist, &collection_artist);
        let score = score(title, &text("trackName"), &text("collectionName"), various);
        if best.as_ref().map_or(true, |b| score > b.score) {
            let released = text("releaseDate");
            let year = released.get(..4).filter(|y| y.chars().all(|c| c.is_ascii_digit())).unwrap_or("").to_string();
            best = Some(Match {
                cover: cover.replace("100x100bb", "1200x1200bb"),
                album: text("collectionName"),
                year,
                duration: song["trackTimeMillis"].as_u64().unwrap_or(0) / 1000,
                deezer_album: String::new(),
                score,
            });
        }
    }
    best
}

/// Release year and label of a match's album ("" where unknown): Deezer's album data.
pub fn details(found: &Match) -> (String, String) {
    static CACHE: OnceLock<Mutex<HashMap<String, (String, String)>>> = OnceLock::new();
    if found.deezer_album.is_empty() {
        return (found.year.clone(), String::new());
    }
    let cache = CACHE.get_or_init(Default::default);
    if let Some(known) = cache.lock().unwrap_or_else(|e| e.into_inner()).get(&found.deezer_album) {
        return known.clone();
    }
    let mut result = (found.year.clone(), String::new());
    if let Some(album) = get_json(&format!("https://api.deezer.com/album/{}", found.deezer_album)) {
        let date = album["release_date"].as_str().unwrap_or("");
        if date.len() >= 4 && date[..4].chars().all(|c| c.is_ascii_digit()) && !date.starts_with("0000") {
            result.0 = date[..4].to_string();
        }
        result.1 = album["label"].as_str().unwrap_or("").to_string();
    }
    cache.lock().unwrap_or_else(|e| e.into_inner()).insert(found.deezer_album.clone(), result.clone());
    result
}

/// DIDL-Lite metadata of a track for the renderer.
pub fn didl(track: &Track, uri: &str, mime: &str) -> String {
    use crate::dlna::escape as e;
    let class = if mime.starts_with("audio") { "object.item.audioItem.musicTrack" } else { "object.item.videoItem" };
    let mut item = format!(
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" \
         xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"ytdlna-{}\" parentID=\"0\" restricted=\"1\">\
         <dc:title>{}</dc:title>",
        e(&track.video_id), e(&track.title)
    );
    if !track.artist.is_empty() {
        item.push_str(&format!("<dc:creator>{0}</dc:creator><upnp:artist>{0}</upnp:artist>", e(&track.artist)));
    }
    if !track.album.is_empty() {
        item.push_str(&format!("<upnp:album>{}</upnp:album>", e(&track.album)));
    }
    if !track.year.is_empty() {
        item.push_str(&format!("<dc:date>{}</dc:date>", e(&track.year)));
    }
    if !track.label.is_empty() {
        item.push_str(&format!("<dc:publisher>{}</dc:publisher>", e(&track.label)));
    }
    if !track.art.is_empty() {
        item.push_str(&format!("<upnp:albumArtURI>{}</upnp:albumArtURI>", e(&track.art)));
    }
    item.push_str(&format!("<upnp:class>{class}</upnp:class><res protocolInfo=\"http-get:*:{mime}:*\""));
    if track.duration > 0 {
        item.push_str(&format!(" duration=\"{}.000\"", crate::dlna::clock(track.duration)));
    }
    item.push_str(&format!(">{}</res></item></DIDL-Lite>", e(uri)));
    item
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cleans_video_titles() {
        assert_eq!(clean_title("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)", "Rick Astley"),
                   "Never Gonna Give You Up");
        assert_eq!(clean_title("Smalltown Boy [HD Remaster]", "Bronski Beat"), "Smalltown Boy");
        assert_eq!(clean_title("Stay feat. Someone", ""), "Stay");
        assert_eq!(artist_name("Rick Astley - Topic"), "Rick Astley");
        assert_eq!(artist_name("RickAstleyVEVO"), "RickAstley");
    }

    #[test]
    fn scores() {
        assert_eq!(score("Never Gonna Give You Up", "Never Gonna Give You Up", "Whenever You Need Somebody", false), 2);
        assert!(score("Never Gonna Give You Up", "Never Gonna Give You Up", "80s Party Hits", false) < GOOD_MATCH);
        assert!(score("Everybody Wants To Rule The World", "Everybody Wants To Rule The World", "Classic 80's", false) < GOOD_MATCH);
        assert!(score("Footloose", "Footloose", "Canciones del Verano de los 80", false) < GOOD_MATCH);
        assert!(similar("Bronski Beat", "Bronski Beat & Marc Almond"));
        assert_eq!(normalize("Beyoncé"), "beyonce");
    }
}
