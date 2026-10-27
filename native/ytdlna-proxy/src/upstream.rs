//! Fetching from YouTube with connection reuse, chunking, read-ahead and retries.

use std::io::{self, Read, Write};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::sync::{OnceLock, RwLock};
use std::thread;
use std::time::Duration;

/// Upper size of one upstream request. Long-running single requests tend to get slow
/// or cut off, smaller ones keep the connection busy and recover quickly.
pub const CHUNK_SIZE: u64 = 8 * 1024 * 1024;
/// Size of the blocks handed from the fetch thread to the writer.
const BLOCK_SIZE: usize = 256 * 1024;
/// Blocks buffered ahead of the renderer: 32 * 256 KiB = 8 MiB read-ahead.
const READ_AHEAD_BLOCKS: usize = 32;
const MAX_ATTEMPTS: u32 = 3;

fn agent() -> &'static ureq::Agent {
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    AGENT.get_or_init(|| {
        ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(10))
            .timeout_read(Duration::from_secs(30))
            .max_idle_connections(16)
            .max_idle_connections_per_host(8)
            .build()
    })
}

fn user_agent_lock() -> &'static RwLock<String> {
    static UA: OnceLock<RwLock<String>> = OnceLock::new();
    UA.get_or_init(|| RwLock::new("Mozilla/5.0".to_string()))
}

pub fn set_user_agent(user_agent: String) {
    *user_agent_lock().write().unwrap_or_else(|e| e.into_inner()) = user_agent;
}

fn user_agent() -> String {
    user_agent_lock().read().unwrap_or_else(|e| e.into_inner()).clone()
}

pub struct Response {
    pub status: u16,
    pub content_length: Option<u64>,
    pub content_range: Option<String>,
    pub reader: Box<dyn Read + Send>,
}

impl Response {
    /// Total size from "Content-Range: bytes a-b/total", or the length of a 200 response.
    pub fn total_length(&self) -> Option<u64> {
        if let Some(range) = &self.content_range {
            return range.rsplit('/').next().and_then(|t| t.trim().parse().ok());
        }
        if self.status == 200 {
            return self.content_length;
        }
        None
    }
}

/// One upstream GET. `range` is the raw Range header value, if any.
pub fn get(url: &str, range: Option<&str>) -> io::Result<Response> {
    let mut request = agent()
        .get(url)
        .set("User-Agent", &user_agent())
        .set("Accept-Encoding", "identity");
    if let Some(range) = range {
        request = request.set("Range", range);
    }
    let response = match request.call() {
        Ok(response) => response,
        Err(ureq::Error::Status(code, response)) => {
            // Read the error body so the connection can be reused.
            let _ = io::copy(&mut response.into_reader().take(64 * 1024), &mut io::sink());
            return Err(io::Error::new(
                io::ErrorKind::Other,
                format!("upstream HTTP {code}"),
            ));
        }
        Err(e) => return Err(io::Error::new(io::ErrorKind::Other, e.to_string())),
    };
    Ok(Response {
        status: response.status(),
        content_length: response.header("Content-Length").and_then(|v| v.parse().ok()),
        content_range: response.header("Content-Range").map(str::to_string),
        reader: Box::new(response.into_reader()),
    })
}

type Block = Result<Vec<u8>, String>;

/// Streams the inclusive byte range `start..=end` of `url` to `out`.
///
/// A background thread fetches the range in chunks and buffers up to 8 MiB ahead, so slow
/// upstream requests and slow renderers do not stall each other. Failed chunks are retried
/// from where they stopped. Returns the number of bytes written.
pub fn stream_range<W: Write>(url: &str, start: u64, end: u64, out: &mut W) -> io::Result<u64> {
    let (sender, receiver) = sync_channel::<Block>(READ_AHEAD_BLOCKS);
    let url = url.to_string();
    let producer = thread::Builder::new()
        .name("ytdlna-fetch".into())
        .spawn(move || fetch_chunks(&url, start, end, sender))?;

    let result = write_blocks(receiver, out);
    // If writing failed the receiver is dropped here and the producer stops at its next send.
    let _ = producer.join();
    result
}

fn write_blocks<W: Write>(receiver: Receiver<Block>, out: &mut W) -> io::Result<u64> {
    let mut written = 0u64;
    for block in receiver {
        match block {
            Ok(data) => {
                out.write_all(&data)?;
                written += data.len() as u64;
            }
            Err(message) => {
                return Err(io::Error::new(io::ErrorKind::Other, message));
            }
        }
    }
    out.flush()?;
    Ok(written)
}

fn fetch_chunks(url: &str, start: u64, end: u64, sender: SyncSender<Block>) {
    let mut position = start;
    while position <= end {
        let chunk_end = end.min(position + CHUNK_SIZE - 1);
        let mut attempt = 0;
        while position <= chunk_end {
            attempt += 1;
            match fetch_into(url, position, chunk_end, &sender) {
                Ok(new_position) => {
                    if new_position == position {
                        // No progress. Count as a failed attempt.
                        if attempt >= MAX_ATTEMPTS {
                            let _ = sender.send(Err("upstream returned no data".into()));
                            return;
                        }
                    }
                    position = new_position;
                }
                Err(FetchError::ReceiverGone) => return,
                Err(FetchError::Upstream(message, new_position)) => {
                    crate::debug!("Chunk {position}-{chunk_end} attempt {attempt} failed: {message}");
                    if new_position > position {
                        // Progress was made, so allow more retries.
                        attempt = 0;
                    }
                    position = new_position;
                    if attempt >= MAX_ATTEMPTS {
                        let _ = sender.send(Err(message));
                        return;
                    }
                    thread::sleep(Duration::from_millis(200 * attempt as u64));
                }
            }
        }
    }
}

enum FetchError {
    ReceiverGone,
    /// Message and the position reached before the error.
    Upstream(String, u64),
}

/// Fetches `position..=end` and sends it on in blocks. Returns the position after the data.
fn fetch_into(url: &str, mut position: u64, end: u64, sender: &SyncSender<Block>) -> Result<u64, FetchError> {
    let range = format!("bytes={position}-{end}");
    let mut response =
        get(url, Some(&range)).map_err(|e| FetchError::Upstream(e.to_string(), position))?;
    if response.status != 206 && !(response.status == 200 && position == 0) {
        return Err(FetchError::Upstream(
            format!("unexpected upstream status {}", response.status),
            position,
        ));
    }

    let mut remaining = end - position + 1;
    while remaining > 0 {
        let mut block = vec![0u8; BLOCK_SIZE.min(remaining as usize)];
        let mut filled = 0;
        while filled < block.len() {
            match response.reader.read(&mut block[filled..]) {
                Ok(0) => break,
                Ok(n) => filled += n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) => {
                    if filled > 0 {
                        block.truncate(filled);
                        if sender.send(Ok(block)).is_err() {
                            return Err(FetchError::ReceiverGone);
                        }
                        position += filled as u64;
                    }
                    return Err(FetchError::Upstream(e.to_string(), position));
                }
            }
        }
        if filled == 0 {
            // Upstream ended early.
            return Err(FetchError::Upstream("upstream ended early".into(), position));
        }
        block.truncate(filled);
        if sender.send(Ok(block)).is_err() {
            return Err(FetchError::ReceiverGone);
        }
        position += filled as u64;
        remaining -= filled as u64;
    }
    Ok(position)
}
