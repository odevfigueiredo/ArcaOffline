package br.com.arca.offline;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Restricted HTTP/1.1 framing for the LAN UI, with one request per TLS connection. */
final class LanHttp {
    private static final int MAX_LINE = 2_048;
    private static final int MAX_HEADER_BYTES = 12 * 1_024;
    private static final int MAX_HEADERS = 32;
    private static final int MAX_BODY = 64 * 1_024;
    private static final Pattern HEADER = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,8}");
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9][0-9]{0,18})");
    private static final Pattern JSON_TYPE = Pattern.compile(
            "application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", Pattern.CASE_INSENSITIVE);
    private static final String RESPONSE_HEADERS =
            "Connection: close\r\n"
            + "Cache-Control: no-store, max-age=0\r\n"
            + "Pragma: no-cache\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "X-Frame-Options: DENY\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "Cross-Origin-Resource-Policy: same-origin\r\n"
            + "Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()\r\n"
            + "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' blob:; frame-src 'none'; connect-src 'self'; base-uri 'none'; form-action 'self'; "
            + "frame-ancestors 'none'; object-src 'none'\r\n";

    private LanHttp() {}

    static final class Request implements AutoCloseable {
        final String method;
        final String path;
        final String query;
        final String cookie;
        final byte[] body;

        Request(String method, String path, String query, String cookie, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.cookie = cookie;
            this.body = body;
        }

        JSONObject json() throws LanServer.ApiException {
            JSONObject result = new JSONObject();
            Set<String> names = new HashSet<>();
            try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(body),
                    StandardCharsets.UTF_8.newDecoder()))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 40 || names.size() >= 16 || !names.add(name)) throw new IOException();
                    JsonToken token = reader.peek();
                    if (token == JsonToken.STRING) {
                        result.put(name, reader.nextString());
                    } else if (token == JsonToken.NUMBER) {
                        String number = reader.nextString();
                        if (!JSON_NUMBER.matcher(number).matches()) throw new IOException();
                        result.put(name, Long.parseLong(number));
                    } else {
                        throw new IOException();
                    }
                }
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
                return result;
            } catch (Exception invalid) {
                throw new LanServer.ApiException(400, "JSON inválido.");
            }
        }

        @Override public void close() {
            Arrays.fill(body, (byte) 0);
        }
    }

    static Request read(InputStream raw, String authority) throws IOException, LanServer.ApiException {
        InputStream in = new BufferedInputStream(raw, 4_096);
        String start = line(in);
        if (start == null) return null;
        String[] parts = start.split(" ", -1);
        if (parts.length != 3 || !"HTTP/1.1".equals(parts[2]) || !parts[1].startsWith("/")) {
            throw badRequest();
        }
        String method = parts[0];
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new LanServer.ApiException(405, "Método indisponível.");
        }
        int separator = parts[1].indexOf('?');
        String path = separator < 0 ? parts[1] : parts[1].substring(0, separator);
        String query = separator < 0 ? null : parts[1].substring(separator + 1);
        if (path.startsWith("//") || parts[1].indexOf('#') >= 0 || parts[1].indexOf('\t') >= 0) throw badRequest();

        Map<String, String> headers = new HashMap<>();
        int headerBytes = start.length() + 2;
        for (;;) {
            String field = line(in);
            if (field == null) throw badRequest();
            if (field.isEmpty()) break;
            headerBytes += field.length() + 2;
            if (headers.size() >= MAX_HEADERS || headerBytes > MAX_HEADER_BYTES) {
                throw new LanServer.ApiException(431, "Cabeçalho acima do limite.");
            }
            int colon = field.indexOf(':');
            if (colon < 1 || !HEADER.matcher(field.substring(0, colon)).matches()) throw badRequest();
            String key = field.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(key, field.substring(colon + 1).trim()) != null) throw badRequest();
        }

        if (!authority.equals(headers.get("host"))) {
            throw new LanServer.ApiException(421, "Use o endereço HTTPS mostrado no celular.");
        }
        if (headers.containsKey("transfer-encoding")) throw badRequest();
        if (headers.containsKey("expect")) throw new LanServer.ApiException(417, "Cabeçalho não aceito.");
        if (headers.containsKey("upgrade")) throw badRequest();
        String origin = headers.get("origin");
        if ((origin != null || "POST".equals(method)) && !("https://" + authority).equals(origin)) {
            throw new LanServer.ApiException(403, "Origem não autorizada.");
        }
        String site = headers.get("sec-fetch-site");
        if (path.startsWith("/api/") && site != null && !"same-origin".equals(site) && !"none".equals(site)) {
            throw new LanServer.ApiException(403, "Origem não autorizada.");
        }

        int size = bodySize(headers.get("content-length"), method);
        if ("POST".equals(method)) {
            String contentType = headers.get("content-type");
            if (contentType == null || !JSON_TYPE.matcher(contentType).matches()
                    || headers.containsKey("content-encoding")) {
                throw new LanServer.ApiException(415, "Envie JSON UTF-8.");
            }
        }
        byte[] body = new byte[size];
        try {
            int offset = 0;
            while (offset < size) {
                int count = in.read(body, offset, size - offset);
                if (count < 0) throw badRequest();
                offset += count;
            }
            return new Request(method, path, query, headers.get("cookie"), body);
        } catch (IOException | LanServer.ApiException failure) {
            Arrays.fill(body, (byte) 0);
            throw failure;
        }
    }

    private static int bodySize(String contentLength, String method) throws LanServer.ApiException {
        if (contentLength == null) {
            if ("GET".equals(method)) return 0;
            throw new LanServer.ApiException(411, "Tamanho do pedido obrigatório.");
        }
        if (!DECIMAL.matcher(contentLength).matches()) throw badRequest();
        int size = Integer.parseInt(contentLength);
        if (size > MAX_BODY) throw new LanServer.ApiException(413, "Pedido acima do limite.");
        if ("GET".equals(method) && size != 0) throw badRequest();
        return size;
    }

    private static String line(InputStream in) throws IOException, LanServer.ApiException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\r') {
                if (in.read() != '\n') throw badRequest();
                return buffer.toString(StandardCharsets.US_ASCII.name());
            }
            if (value != '\t' && (value < 32 || value > 126)) throw badRequest();
            if (buffer.size() >= MAX_LINE) throw new LanServer.ApiException(431, "Cabeçalho acima do limite.");
            buffer.write(value);
        }
        if (buffer.size() != 0) throw badRequest();
        return null;
    }

    private static LanServer.ApiException badRequest() {
        return new LanServer.ApiException(400, "Pedido inválido.");
    }

    static void write(OutputStream out, int status, String mime, byte[] body, String cookie,
                      String disposition) throws IOException {
        StringBuilder header = new StringBuilder(768);
        header.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                .append(RESPONSE_HEADERS)
                .append("Content-Type: ").append(mime).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n");
        if (cookie != null) header.append("Set-Cookie: ").append(cookie).append("\r\n");
        if (disposition != null) header.append("Content-Disposition: ").append(disposition).append("\r\n");
        if (status == 405) header.append("Allow: GET, POST\r\n");
        if (status == 429) header.append("Retry-After: 60\r\n");
        header.append("\r\n");
        byte[] fields = header.toString().getBytes(StandardCharsets.US_ASCII);
        if (status >= 400) {
            // Entrega erros curtos no mesmo registro TLS, inclusive se o corpo hostil ainda estiver chegando.
            byte[] packet = Arrays.copyOf(fields, fields.length + body.length);
            System.arraycopy(body, 0, packet, fields.length, body.length);
            try {
                out.write(packet);
            } finally {
                Arrays.fill(packet, (byte) 0);
            }
        } else {
            out.write(fields);
            out.write(body);
        }
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Content Too Large";
            case 415: return "Unsupported Media Type";
            case 417: return "Expectation Failed";
            case 421: return "Misdirected Request";
            case 422: return "Unprocessable Content";
            case 423: return "Locked";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            default: return "Internal Server Error";
        }
    }
}
