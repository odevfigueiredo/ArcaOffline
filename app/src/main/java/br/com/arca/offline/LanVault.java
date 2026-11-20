package br.com.arca.offline;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/** All snapshots and changes pass through the vault's single disk writer. */
final class LanVault implements LanServer.Vault {
    interface Committer {
        void write(JSONObject next, Runnable checkAccess) throws Exception;
    }

    private final ExecutorService storage;
    private final Committer commit;
    private final Path file;
    private final List<String> ids = new ArrayList<>();
    private JSONObject data;
    private volatile boolean active = true;
    private long revision = 1;

    LanVault(ExecutorService storage, JSONObject initial, Path file, Committer commit) {
        this.storage = storage;
        this.file = file;
        this.data = initial;
        this.commit = commit;
        for (int index = 0; index < rows().length(); index++) ids.add(newId());
    }

    void revoke() {
        active = false;
    }

    JSONObject settledData() {
        return data;
    }

    private JSONArray rows() {
        return data.optJSONArray("records");
    }

    private void checkActive() {
        if (!active) throw new java.util.concurrent.CancellationException();
    }

    private <T> T access(Callable<T> action) throws Exception {
        try {
            return storage.submit(() -> {
                checkActive();
                return action.call();
            }).get();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof java.util.concurrent.CancellationException) {
                throw new LanServer.ApiException(423, "O acesso web foi encerrado no celular.");
            }
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    @Override public JSONObject state() throws Exception {
        return access(this::encodeState);
    }

    @Override public JSONObject save(JSONObject request) throws Exception {
        return access(() -> {
            requireRevision(request);
            String id = text(request, "id", 36);
            int index = id.isEmpty() ? -1 : checkId(id);
            JSONObject replacement = index == -1 ? new JSONObject() : VaultData.copyObject(rows().getJSONObject(index));
            if (index == -1) {
                if (ids.size() >= VaultData.MAX_RECORDS) {
                    throw new LanServer.ApiException(422, "O cofre atingiu o limite de registros.");
                }
                replacement.put("id", newId());
            }

            String title = text(request, "title", 120).trim();
            String category = text(request, "category", 30);
            String date = text(request, "date", 10).trim();
            if (title.isEmpty() || !Arrays.asList(VaultData.CATEGORIES).contains(category)) {
                throw new LanServer.ApiException(422, "Preencha o título e selecione uma categoria.");
            }
            if (!date.isEmpty()) {
                try {
                    LocalDate.parse(date);
                    if (date.length() != 10) throw new DateTimeParseException("Formato", date, 0);
                } catch (DateTimeParseException invalid) {
                    throw new LanServer.ApiException(422, "Digite uma data válida.");
                }
            }

            replacement.put("title", title);
            replacement.put("category", category);
            replacement.put("notes", text(request, "notes", 5_000));
            replacement.put("phone", text(request, "phone", 120).trim());
            replacement.put("date", date);
            write(VaultData.replaceRecord(data, index, replacement));
            if (index == -1) ids.add(newId());
            return encodeState();
        });
    }

    @Override public JSONObject delete(JSONObject request) throws Exception {
        return access(() -> {
            requireRevision(request);
            int index = checkId(text(request, "id", 36));
            write(VaultData.replaceRecord(data, index, null));
            ids.remove(index);
            return encodeState();
        });
    }

    private void write(JSONObject next) throws Exception {
        commit.write(next, this::checkActive);
        data = next;
        revision++;
    }

    @Override public LanServer.Download attachment(String id) throws Exception {
        return access(() -> {
            JSONObject record = rows().getJSONObject(checkId(id));
            String encoded = record.optString("attachmentData");
            if (encoded.isEmpty()) throw new LanServer.ApiException(404, "O registro não tem anexo.");
            return new LanServer.Download(MainActivity.safeName(record.optString("attachmentName")),
                    Base64.decode(encoded, Base64.NO_WRAP));
        });
    }

    @Override public LanServer.Download backup() throws Exception {
        return access(() -> new LanServer.Download("arca-backup-" + LocalDate.now() + ".arca",
                VaultStorage.readPrivate(file, CryptoBox.MAX_ARCHIVE_BYTES)));
    }

    private JSONObject encodeState() throws Exception {
        JSONArray entries = new JSONArray();
        for (int index = rows().length() - 1; index >= 0; index--) {
            JSONObject record = rows().getJSONObject(index);
            JSONObject item = new JSONObject().put("id", ids.get(index));
            for (String name : new String[]{"title", "category", "notes", "phone", "date"}) {
                item.put(name, record.optString(name));
            }
            Object attachment = JSONObject.NULL;
            if (!record.optString("attachmentData").isEmpty()) {
                attachment = new JSONObject()
                        .put("name", MainActivity.safeName(record.optString("attachmentName")))
                        .put("mime", MainActivity.safeMime(record.optString("attachmentMime")));
            }
            entries.put(item.put("attachment", attachment));
        }
        return new JSONObject().put("revision", revision).put("records", entries)
                .put("categories", new JSONArray(Arrays.asList(VaultData.CATEGORIES)));
    }

    private void requireRevision(JSONObject request) throws LanServer.ApiException {
        Object value = request.opt("revision");
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() != revision) {
            throw new LanServer.ApiException(409, "O cofre mudou. Atualize a lista antes de continuar.");
        }
    }

    private int checkId(String id) throws LanServer.ApiException {
        int index = ids.indexOf(id);
        if (index < 0) throw new LanServer.ApiException(404, "O registro não está mais disponível.");
        return index;
    }

    private static String text(JSONObject object, String key, int max) throws LanServer.ApiException {
        Object value = object.opt(key);
        if (value == null) return "";
        if (!(value instanceof String) || ((String) value).length() > max) {
            throw new LanServer.ApiException(422, "Um dos campos do registro está fora do limite.");
        }
        return (String) value;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
