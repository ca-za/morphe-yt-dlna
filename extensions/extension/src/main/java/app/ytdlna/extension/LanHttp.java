package app.ytdlna.extension;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny HTTP/1.1 client over a plain socket, for talking to devices on the local network.
 * The patched apps forbid cleartext traffic for HttpURLConnection, but DLNA is plain HTTP.
 */
final class LanHttp {
    static final int TIMEOUT_MS = 5000;

    static final class Response {
        final int status;
        final Map<String, String> headers; // Lower case names.
        final byte[] body;

        Response(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private LanHttp() {
    }

    static Response get(String url) throws IOException {
        return request("GET", url, null, null);
    }

    static Response request(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        URL u = new URL(url);
        if (!"http".equals(u.getProtocol())) throw new IOException("Only http is supported: " + url);
        int port = u.getPort() == -1 ? 80 : u.getPort();
        String path = u.getFile().isEmpty() ? "/" : u.getFile();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(u.getHost(), port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(u.getHost()).append(':').append(port).append("\r\n");
            head.append("Connection: close\r\n");
            for (Map.Entry<String, String> h : ControlPoint.headers().entrySet()) {
                if (headers == null || !headers.containsKey(h.getKey())) {
                    head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
                }
            }
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
                }
            }
            if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) out.write(body);
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            String statusLine = readLine(in);
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2) throw new IOException("Bad status line: " + statusLine);
            int status = Integer.parseInt(parts[1]);

            Map<String, String> responseHeaders = readHeaders(in);
            byte[] responseBody;
            String transferEncoding = responseHeaders.get("transfer-encoding");
            String contentLength = responseHeaders.get("content-length");
            if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
                responseBody = readChunked(in);
            } else if (contentLength != null) {
                responseBody = readExactly(in, Integer.parseInt(contentLength.trim()));
            } else {
                responseBody = readAll(in);
            }
            return new Response(status, responseHeaders, responseBody);
        }
    }

    /** Reads a CRLF (or LF) terminated line, without the terminator. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 16 * 1024) throw new IOException("Line too long");
        }
        if (c == -1 && line.size() == 0) throw new IOException("Connection closed");
        return line.toString("UTF-8");
    }

    /** Reads headers up to the empty line. Names are lower case. */
    static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            int semicolon = sizeLine.indexOf(';');
            if (semicolon >= 0) sizeLine = sizeLine.substring(0, semicolon);
            int size = Integer.parseInt(sizeLine.trim(), 16);
            if (size == 0) {
                // Skip trailers.
                while (!readLine(in).isEmpty()) ;
                return out.toByteArray();
            }
            out.write(readExactly(in, size));
            readLine(in);
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buf, read, length - read);
            if (n == -1) throw new IOException("Unexpected end of body");
            read += n;
        }
        return buf;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
