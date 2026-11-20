package br.com.arca.offline;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** Listener HTTPS temporário, limitado ao endereço e à sub-rede selecionados no aparelho. */
final class LanServer implements AutoCloseable {
    private static final long PAIR_TIME_MS = 3 * 60_000L;
    private static final long IDLE_TIME_MS = 5 * 60_000L;
    private static final long TOTAL_TIME_MS = 20 * 60_000L;
    private static final long RATE_WINDOW_MS = 60_000L;
    private static final int MAX_FAILED_PAIRS = 6;
    private static final int MAX_CLIENTS = 4;
    private static final long REQUEST_TIMEOUT_MS = 8_000L;
    private static final long RESPONSE_TIMEOUT_MS = 50_000L;
    private static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STATE_BYTES = 16 * 1024 * 1024;
    private static final String JSON = "application/json; charset=utf-8";
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final String COOKIE = "__Host-arca";
    private static final String COOKIE_FLAGS = "; Path=/; Secure; HttpOnly; SameSite=Strict";
    private static final String CLEAR_COOKIE = COOKIE + "=; Max-Age=0" + COOKIE_FLAGS;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Set<String> CIPHERS = new HashSet<>(Arrays.asList(
            "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"));
    private static final String HEX = "0123456789ABCDEF";
    private static final SecureRandom RANDOM = new SecureRandom();

    interface Vault {
        JSONObject state() throws Exception;
        JSONObject save(JSONObject request) throws Exception;
        JSONObject delete(JSONObject request) throws Exception;
        Download attachment(String id) throws Exception;
        Download backup() throws Exception;
    }

    static final class Download {
        final String filename;
        final byte[] bytes;

        Download(String filename, byte[] bytes) {
            this.filename = filename;
            this.bytes = bytes;
        }
    }

    static final class ApiException extends Exception {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Context context;
    private final Inet4Address address;
    private final String interfaceName;
    private final int prefix;
    private final int netmask;
    private final SSLServerSocket listener;
    private final String authority;
    private final Vault vault;
    private final Runnable onStopped;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean vaultResponse = new AtomicBoolean();
    private final ConcurrentHashMap<SSLSocket, Long> clients = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(MAX_CLIENTS, MAX_CLIENTS, 0,
            TimeUnit.MILLISECONDS, new SynchronousQueue<>(), work -> new Thread(work, "arca-https-client"));
    private final long pairDeadline;
    private final long totalDeadline;
    private String displayCode;
    private byte[] pairingSecret;
    private byte[] sessionToken;
    private long idleDeadline;
    private long rateDeadline;
    private int failedPairs;
    private boolean everPaired;
    private boolean loggingOut;

    static LanServer start(Context context, Inet4Address address, SSLContext tls, Vault vault,
                           Runnable onStopped) throws IOException {
        NetworkInterface network = NetworkInterface.getByInetAddress(address);
        int prefix = usablePrefix(network, address);
        if (!address.isSiteLocalAddress() || prefix < 8 || prefix > 32) {
            throw new IOException("O endereço da rede local ficou indisponível.");
        }
        SSLServerSocket listener = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
        try {
            configure(listener);
            listener.bind(new InetSocketAddress(address, 0), MAX_CLIENTS);
            listener.setSoTimeout(250);
            LanServer server = new LanServer(context.getApplicationContext(), address, network.getName(), prefix,
                    listener, vault, onStopped);
            new Thread(server::accept, "arca-https-listener").start();
            return server;
        } catch (IOException | RuntimeException failure) {
            listener.close();
            throw failure;
        }
    }

    private LanServer(Context context, Inet4Address address, String interfaceName, int prefix,
                      SSLServerSocket listener, Vault vault, Runnable onStopped) {
        this.context = context;
        this.address = address;
        this.interfaceName = interfaceName;
        this.prefix = prefix;
        netmask = -1 << (32 - prefix);
        this.listener = listener;
        this.vault = vault;
        this.onStopped = onStopped;
        authority = address.getHostAddress() + ":" + listener.getLocalPort();
        long now = SystemClock.elapsedRealtime();
        pairDeadline = now + PAIR_TIME_MS;
        totalDeadline = now + TOTAL_TIME_MS;
        pairingSecret = new byte[16];
        StringBuilder formatted = new StringBuilder(19);
        for (int i = 0; i < pairingSecret.length; i++) {
            if (i > 0 && i % 4 == 0) formatted.append('-');
            char symbol = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
            pairingSecret[i] = (byte) symbol;
            formatted.append(symbol);
        }
        displayCode = formatted.toString();
    }

    private static void configure(SSLServerSocket listener) throws IOException {
        List<String> protocols = new ArrayList<>();
        for (String protocol : listener.getSupportedProtocols()) {
            if ("TLSv1.3".equals(protocol) || "TLSv1.2".equals(protocol)) protocols.add(protocol);
        }
        List<String> ciphers = new ArrayList<>();
        for (String cipher : listener.getSupportedCipherSuites()) {
            if (CIPHERS.contains(cipher)) ciphers.add(cipher);
        }
        if (protocols.isEmpty() || ciphers.isEmpty()) throw new IOException("TLS 1.2 ou superior indisponível.");
        listener.setEnabledProtocols(protocols.toArray(new String[0]));
        listener.setEnabledCipherSuites(ciphers.toArray(new String[0]));
    }

    String origin() {
        return "https://" + authority;
    }

    synchronized String pairingCode() {
        return displayCode;
    }

    synchronized boolean isPaired() {
        return isRunningAt(SystemClock.elapsedRealtime()) && sessionToken != null;
    }

    synchronized boolean isRunning() {
        return !loggingOut && keepListening();
    }

    private synchronized boolean keepListening() {
        return isRunningAt(SystemClock.elapsedRealtime());
    }

    private boolean isRunningAt(long now) {
        return !closed.get() && now < totalDeadline && now < (everPaired ? idleDeadline : pairDeadline);
    }

    private void accept() {
        long nextNetworkCheck = 0;
        try {
            while (keepListening()) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextNetworkCheck) {
                    NetworkInterface current = NetworkInterface.getByInetAddress(address);
                    if (current == null || usablePrefix(current, address) != prefix
                            || !interfaceName.equals(current.getName())) break;
                    nextNetworkCheck = now + 1_000L;
                }
                for (Map.Entry<SSLSocket, Long> client : clients.entrySet()) {
                    if (client.getValue() <= now && clients.remove(client.getKey(), client.getValue())) {
                        closeSocket(client.getKey());
                    }
                }
                try {
                    SSLSocket socket = (SSLSocket) listener.accept();
                    acceptClient(socket);
                } catch (SocketTimeoutException retry) {
                    // O mesmo laço limita conexões e expira a sessão mesmo sem requisições.
                }
            }
        } catch (IOException | RuntimeException stopped) {
            // O estado exibido é atualizado pelo callback de encerramento.
        } finally {
            close();
        }
    }

    private void acceptClient(SSLSocket socket) {
        InetAddress peer = socket.getInetAddress();
        if (!(peer instanceof Inet4Address) || !peer.isSiteLocalAddress()
                || (ipv4(peer) & netmask) != (ipv4(address) & netmask)) {
            closeSocket(socket);
            return;
        }
        synchronized (this) {
            if (!isRunning()) {
                closeSocket(socket);
                return;
            }
            clients.put(socket, SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS);
        }
        try {
            workers.execute(() -> serve(socket));
        } catch (RejectedExecutionException busy) {
            clients.remove(socket);
            closeSocket(socket);
        }
    }

    private static int usablePrefix(NetworkInterface network, Inet4Address address) throws IOException {
        if (network == null || !network.isUp() || network.isLoopback() || network.isPointToPoint()) return -1;
        for (InterfaceAddress entry : network.getInterfaceAddresses()) {
            if (address.equals(entry.getAddress())) return entry.getNetworkPrefixLength();
        }
        return -1;
    }

    private static int ipv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        return ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16) | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
    }

    private void serve(SSLSocket socket) {
        Response response = null;
        try (SSLSocket connection = socket) {
            connection.setSoTimeout((int) REQUEST_TIMEOUT_MS);
            connection.startHandshake();
            try (LanHttp.Request request = LanHttp.read(connection.getInputStream(), authority)) {
                if (request == null) return;
                clients.computeIfPresent(connection, (key, previous) -> SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS);
                try {
                    response = dispatch(request, connection);
                } catch (ApiException rejected) {
                    throw rejected;
                } catch (Exception failure) {
                    response = error(500, "Não foi possível concluir o pedido.");
                }
            } catch (ApiException rejected) {
                response = error(rejected.status, rejected.getMessage());
            }
            if ((response.stopAfter || isRunning()) && clients.containsKey(connection)) {
                LanHttp.write(connection.getOutputStream(), response.status, response.type, response.body,
                        response.cookie, response.disposition);
            }
        } catch (IOException ignored) {
            // TLS, cancelamento e timeouts não registram dados de clientes.
        } finally {
            clients.remove(socket);
            if (response != null) {
                Arrays.fill(response.body, (byte) 0);
                if (response.usesVaultSlot) vaultResponse.set(false);
                if (response.stopAfter) close();
            }
        }
    }

    private Response dispatch(LanHttp.Request request, SSLSocket connection) throws Exception {
        if (!isRunning()) throw new ApiException(503, "O acesso local foi encerrado.");
        if (!request.path.startsWith("/api/")) {
            requireMethod(request, "GET");
            noQuery(request);
            return asset(request.path);
        }
        if ("/api/pair".equals(request.path)) {
            requireMethod(request, "POST");
            noQuery(request);
            JSONObject payload = request.json();
            if (payload.length() != 1 || !(payload.opt("code") instanceof String)) {
                throw new ApiException(400, "Informe o código mostrado no celular.");
            }
            Response reply = json(new JSONObject().put("ok", true));
            reply.cookie = pair(payload.getString("code"));
            return reply;
        }
        if ("/api/status".equals(request.path)) {
            requireMethod(request, "GET");
            noQuery(request);
            return json(status(request.cookie));
        }
        requireSession(request.cookie);
        if ("/api/logout".equals(request.path)) {
            requireMethod(request, "POST");
            noQuery(request);
            if (request.json().length() != 0) throw new ApiException(400, "Pedido inválido.");
            return logout(connection);
        }
        if (!vaultResponse.compareAndSet(false, true)) {
            throw new ApiException(503, "Há outro pedido do cofre em andamento. Tente de novo.");
        }
        boolean keepSlot = false;
        try {
            Response response = vaultRoute(request);
            response.usesVaultSlot = true;
            keepSlot = true;
            return response;
        } finally {
            if (!keepSlot) vaultResponse.set(false);
        }
    }

    private Response vaultRoute(LanHttp.Request request) throws Exception {
        switch (request.path) {
            case "/api/state":
                requireMethod(request, "GET");
                noQuery(request);
                return json(vault.state());
            case "/api/record":
                requireMethod(request, "POST");
                noQuery(request);
                return json(vault.save(request.json()));
            case "/api/delete":
                requireMethod(request, "POST");
                noQuery(request);
                return json(vault.delete(request.json()));
            case "/api/attachment":
                requireMethod(request, "GET");
                return download(vault.attachment(attachmentId(request.query)), VaultData.MAX_ATTACHMENT);
            case "/api/backup":
                requireMethod(request, "GET");
                noQuery(request);
                return download(vault.backup(), CryptoBox.MAX_ARCHIVE_BYTES);
            default:
                throw new ApiException(404, "Recurso indisponível.");
        }
    }

    private static void requireMethod(LanHttp.Request request, String method) throws ApiException {
        if (!method.equals(request.method)) throw new ApiException(405, "Método indisponível.");
    }

    private static void noQuery(LanHttp.Request request) throws ApiException {
        if (request.query != null) throw new ApiException(400, "Parâmetro inesperado.");
    }

    private static String attachmentId(String query) throws ApiException {
        if (query == null || !query.startsWith("id=") || query.indexOf('&') >= 0) {
            throw new ApiException(400, "Identificador de anexo inválido.");
        }
        try {
            String id = URLDecoder.decode(query.substring(3), StandardCharsets.UTF_8.name());
            if (id.isEmpty() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl)) {
                throw new IOException();
            }
            return id;
        } catch (IllegalArgumentException | IOException invalid) {
            throw new ApiException(400, "Identificador de anexo inválido.");
        }
    }

    private synchronized String pair(String code) throws ApiException {
        long now = SystemClock.elapsedRealtime();
        if (!isRunningAt(now) || everPaired) throw new ApiException(409, "O código já foi usado ou expirou.");
        if (now >= rateDeadline) {
            rateDeadline = now + RATE_WINDOW_MS;
            failedPairs = 0;
        }
        if (failedPairs >= MAX_FAILED_PAIRS) throw new ApiException(429, "Aguarde um minuto para tentar novamente.");
        byte[] candidate = normalizeCode(code);
        boolean valid = candidate != null && MessageDigest.isEqual(pairingSecret, candidate);
        if (candidate != null) Arrays.fill(candidate, (byte) 0);
        if (!valid) {
            failedPairs++;
            throw new ApiException(401, "Código incorreto.");
        }
        byte[] unique = new byte[32];
        RANDOM.nextBytes(unique);
        sessionToken = Base64.getUrlEncoder().withoutPadding().encode(unique);
        Arrays.fill(unique, (byte) 0);
        everPaired = true;
        idleDeadline = now + IDLE_TIME_MS;
        clearPairingCode();
        return COOKIE + "=" + new String(sessionToken, StandardCharsets.US_ASCII) + COOKIE_FLAGS;
    }

    private static byte[] normalizeCode(String code) {
        if (code.length() > 40) return null;
        byte[] result = new byte[16];
        int count = 0;
        for (int i = 0; i < code.length(); i++) {
            char symbol = code.charAt(i);
            if (symbol == '-' || symbol == ' ') continue;
            if (symbol >= 'a' && symbol <= 'z') symbol -= 'a' - 'A';
            if (count == result.length || ALPHABET.indexOf(symbol) < 0) {
                Arrays.fill(result, (byte) 0);
                return null;
            }
            result[count++] = (byte) symbol;
        }
        if (count == result.length) return result;
        Arrays.fill(result, (byte) 0);
        return null;
    }

    private synchronized JSONObject status(String cookie) throws JSONException {
        return new JSONObject().put("paired", everPaired).put("authenticated",
                isRunningAt(SystemClock.elapsedRealtime()) && authentic(cookie));
    }

    private synchronized void requireSession(String cookie) throws ApiException {
        long now = SystemClock.elapsedRealtime();
        if (!isRunningAt(now) || !authentic(cookie)) {
            throw new ApiException(401, "Sessão local indisponível ou encerrada.");
        }
        idleDeadline = now + IDLE_TIME_MS;
    }

    private boolean authentic(String cookies) {
        if (sessionToken == null || cookies == null || loggingOut) return false;
        String value = null;
        for (String item : cookies.split(";")) {
            String cookie = item.trim();
            if (!cookie.startsWith(COOKIE + "=")) continue;
            if (value != null) return false;
            value = cookie.substring(COOKIE.length() + 1);
        }
        if (value == null || !TOKEN.matcher(value).matches()) return false;
        byte[] candidate = value.getBytes(StandardCharsets.US_ASCII);
        try {
            return MessageDigest.isEqual(sessionToken, candidate);
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private synchronized Response logout(SSLSocket connection) throws JSONException, ApiException {
        if (loggingOut) throw new ApiException(401, "Sessão local encerrada.");
        Response response = json(new JSONObject().put("ok", true));
        loggingOut = true;
        clearToken();
        response.cookie = CLEAR_COOKIE;
        response.stopAfter = true;
        for (Map.Entry<SSLSocket, Long> client : clients.entrySet()) {
            // O socket que responde ao logout permanece aberto até enviar o resultado.
            if (client.getKey() != connection) closeSocket(client.getKey());
        }
        return response;
    }

    private Response asset(String path) throws Exception {
        String file;
        String mime;
        switch (path) {
            case "/":
            case "/index.html":
                file = "index.html"; mime = "text/html; charset=utf-8"; break;
            case "/app.js":
                file = "app.js"; mime = "application/javascript; charset=utf-8"; break;
            case "/styles.css":
                file = "styles.css"; mime = "text/css; charset=utf-8"; break;
            case "/arca-vault.webp":
                file = "arca-vault.webp"; mime = "image/webp"; break;
            default:
                throw new ApiException(404, "Recurso indisponível.");
        }
        InputStream source = context.getAssets().open("lan/" + file);
        return new Response(200, mime, VaultStorage.read(source, MAX_ASSET_BYTES));
    }

    private static Response json(JSONObject result) throws ApiException {
        byte[] body = result.toString().getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_STATE_BYTES) {
            Arrays.fill(body, (byte) 0);
            throw new ApiException(413, "Resposta acima do limite.");
        }
        return new Response(200, JSON, body);
    }

    private static Response error(int status, String message) {
        JSONObject value = new JSONObject();
        try {
            value.put("error", message);
        } catch (JSONException impossible) {
            return new Response(status, JSON, new byte[] {'{', '}'});
        }
        return new Response(status, JSON, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Response download(Download file, int limit) throws ApiException {
        if (file == null || file.bytes == null) throw new ApiException(404, "Arquivo indisponível.");
        if (file.bytes.length > limit) {
            Arrays.fill(file.bytes, (byte) 0);
            throw new ApiException(413, "Arquivo acima do limite.");
        }
        Response response = new Response(200, "application/octet-stream", file.bytes);
        response.disposition = disposition(file.filename);
        return response;
    }

    private static String disposition(String raw) {
        String name = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        if (name.length() > 140) name = name.substring(0, 140);
        if (name.trim().isEmpty() || ".".equals(name) || "..".equals(name)) name = "arquivo";
        String fallback = name.replaceAll("[^0-9A-Za-z ._-]", "_");
        StringBuilder utf8 = new StringBuilder();
        for (byte item : name.getBytes(StandardCharsets.UTF_8)) {
            int octet = item & 255;
            utf8.append('%').append(HEX.charAt(octet >> 4)).append(HEX.charAt(octet & 15));
        }
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + utf8;
    }

    private static final class Response {
        final int status;
        final String type;
        final byte[] body;
        String cookie;
        String disposition;
        boolean stopAfter;
        boolean usesVaultSlot;

        Response(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }
    }

    private void clearPairingCode() {
        if (pairingSecret != null) Arrays.fill(pairingSecret, (byte) 0);
        pairingSecret = null;
        displayCode = null;
    }

    private void clearToken() {
        if (sessionToken != null) Arrays.fill(sessionToken, (byte) 0);
        sessionToken = null;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (this) {
            clearPairingCode();
            clearToken();
        }
        try {
            listener.close();
        } catch (IOException ignored) {
            // A limpeza dos demais sockets ainda precisa ocorrer.
        }
        for (SSLSocket client : clients.keySet()) closeSocket(client);
        clients.clear();
        workers.shutdownNow();
        if (onStopped != null) onStopped.run();
    }

    private static void closeSocket(SSLSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // O socket não será reutilizado.
        }
    }
}
