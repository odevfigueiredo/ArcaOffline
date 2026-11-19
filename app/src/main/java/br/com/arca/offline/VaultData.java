package br.com.arca.offline;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Iterator;

/** Validação e alterações no JSON v1 do cofre. Os objetos já publicados são somente leitura. */
final class VaultData {
    static final int MAX_RECORDS = 350;
    static final int MAX_ATTACHMENT = 8 * 1024 * 1024;
    static final String[] CATEGORIES = {"Documento", "Contato", "Saúde", "Seguro", "Finanças", "Emergência", "Outros"};

    private VaultData() {}

    static JSONObject empty() throws JSONException {
        return new JSONObject().put("version", 1).put("records", new JSONArray());
    }

    static JSONObject parse(byte[] plain) throws Exception {
        JSONObject vault;
        try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(plain),
                StandardCharsets.UTF_8.newDecoder()))) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new JSONException("Conteúdo inválido.");
            vault = (JSONObject) readValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("Conteúdo extra no cofre.");
        }
        Object version = vault.opt("version");
        if (!(version instanceof Integer) || ((Integer) version) != 1) {
            throw new JSONException("Versão de cofre incompatível.");
        }
        JSONArray records = vault.getJSONArray("records");
        if (records.length() > MAX_RECORDS) {
            throw new JSONException("O cofre excede o limite de registros.");
        }
        for (int i = 0; i < records.length(); i++) {
            validateRecord(records.getJSONObject(i));
        }
        return vault;
    }

    static byte[] encode(JSONObject data) throws IOException, JSONException {
        // A primeira passagem mede o UTF-8 sem criar uma cópia integral do JSON como String.
        EncodedOutput measure = new EncodedOutput(null);
        writeJson(data, measure);
        byte[] plain = new byte[measure.length];
        try {
            writeJson(data, new EncodedOutput(plain));
            return plain;
        } catch (Exception e) {
            Arrays.fill(plain, (byte) 0);
            throw e;
        }
    }

    private static void writeJson(JSONObject data, OutputStream output) throws IOException, JSONException {
        try (JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8)))) {
            writeValue(writer, data);
        }
    }

    private static void writeValue(JsonWriter writer, Object value) throws IOException, JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            writer.beginObject();
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String key = keys.next();
                writer.name(key);
                writeValue(writer, object.get(key));
            }
            writer.endObject();
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            writer.beginArray();
            for (int i = 0; i < array.length(); i++) writeValue(writer, array.get(i));
            writer.endArray();
        } else if (value instanceof String) {
            writer.value((String) value);
        } else if (value instanceof Number) {
            writer.value((Number) value);
        } else if (value instanceof Boolean) {
            writer.value((Boolean) value);
        } else {
            writer.nullValue();
        }
    }

    private static final class EncodedOutput extends OutputStream {
        private final byte[] data;
        private final int max;
        private int length;

        EncodedOutput(byte[] data) {
            this.data = data;
            max = data == null ? CryptoBox.MAX_PLAINTEXT_BYTES : data.length;
        }

        @Override public void write(int value) throws IOException {
            int start = reserve(1);
            if (data != null) data[start] = (byte) value;
        }

        @Override public void write(byte[] source, int start, int count) throws IOException {
            int offset = reserve(count);
            if (data != null) System.arraycopy(source, start, data, offset, count);
        }

        private int reserve(int count) throws IOException {
            if (count > max - length) throw new IOException("O cofre excede 32 MiB.");
            int start = length;
            length += count;
            return start;
        }
    }

    private static Object readValue(JsonReader reader, int depth) throws IOException, JSONException {
        if (depth > 8) throw new JSONException("Estrutura de cofre inválida.");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key) || object.length() >= 64) throw new JSONException("Estrutura de cofre inválida.");
                    object.put(key, readValue(reader, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (array.length() >= MAX_RECORDS) throw new JSONException("O cofre excede o limite de registros.");
                    array.put(readValue(reader, depth + 1));
                }
                reader.endArray();
                return array;
            }
            case STRING:
                return reader.nextString();
            case BOOLEAN:
                return reader.nextBoolean();
            case NUMBER: {
                String number = reader.nextString();
                try {
                    return Integer.valueOf(number);
                } catch (NumberFormatException notInt) {
                    try {
                        return Long.valueOf(number);
                    } catch (NumberFormatException notLong) {
                        return Double.valueOf(number);
                    }
                }
            }
            case NULL:
                reader.nextNull();
                return JSONObject.NULL;
            default:
                throw new JSONException("Conteúdo inválido.");
        }
    }

    static JSONObject replaceRecord(JSONObject vault, int index, JSONObject replacement) throws JSONException {
        JSONArray before = vault.getJSONArray("records");
        JSONArray after = new JSONArray();
        for (int i = 0; i < before.length(); i++) {
            if (i != index) {
                after.put(before.getJSONObject(i));
            } else if (replacement != null) {
                after.put(replacement);
            }
        }
        if (index == -1) after.put(replacement);
        if (after.length() > MAX_RECORDS) throw new JSONException("O cofre atingiu o limite de registros.");
        JSONObject next = copyObject(vault);
        return next.put("records", after);
    }

    static JSONObject copyObject(JSONObject original) throws JSONException {
        JSONObject copy = new JSONObject();
        for (Iterator<String> keys = original.keys(); keys.hasNext(); ) {
            String key = keys.next();
            copy.put(key, original.get(key));
        }
        return copy;
    }

    private static void validateRecord(JSONObject record) throws JSONException {
        String title = field(record, "title", 130);
        if (title.trim().isEmpty()) throw new JSONException("Registro sem título.");
        String category = field(record, "category", 30);
        if (!category.isEmpty() && !Arrays.asList(CATEGORIES).contains(category)) {
            throw new JSONException("Categoria inválida.");
        }
        field(record, "id", 128);
        field(record, "notes", 6000);
        field(record, "phone", 120);
        String date = field(record, "date", 32);
        if (!date.isEmpty()) {
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException invalidDate) {
                throw new JSONException("Data de registro inválida.");
            }
        }
        field(record, "attachmentName", 140);
        field(record, "attachmentMime", CryptoBox.MAX_PLAINTEXT_BYTES);
        validateAttachment(field(record, "attachmentData", ((MAX_ATTACHMENT + 2) / 3) * 4));
    }

    private static String field(JSONObject object, String key, int maxLength) throws JSONException {
        if (!object.has(key)) return "";
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).length() > maxLength) {
            throw new JSONException("Campo inválido em registro.");
        }
        return (String) value;
    }

    private static void validateAttachment(String data) throws JSONException {
        int length = data.length();
        if (length == 0) return;
        if (length % 4 != 0) throw new JSONException("Anexo inválido.");
        int payload = length;
        if (data.charAt(payload - 1) == '=') payload--;
        if (data.charAt(payload - 1) == '=') payload--;
        if (length / 4 * 3 - (length - payload) > MAX_ATTACHMENT) {
            throw new JSONException("Anexo excede 8 MiB.");
        }
        for (int i = 0; i < payload; i++) {
            char c = data.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/') continue;
            throw new JSONException("Anexo inválido.");
        }
    }

}
