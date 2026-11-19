package br.com.arca.offline;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Operações de arquivo do cofre privado e limites para entradas externas. */
final class VaultStorage {
    private static final int BUFFER_SIZE = 16 * 1024;

    private VaultStorage() {}

    static byte[] read(InputStream stream, int max) throws IOException {
        try (InputStream source = stream) {
            if (source == null) throw new IOException("Arquivo indisponível.");
            return readChunks(source, max);
        }
    }

    static byte[] readPrivate(Path path, int max) throws IOException {
        long length = Files.size(path);
        if (length > max) throw new IOException("Arquivo excede o limite de tamanho.");
        byte[] data = new byte[(int) length];
        boolean complete = false;
        try (InputStream source = Files.newInputStream(path)) {
            if (readUpTo(source, data) != data.length || source.read() != -1) {
                throw new EOFException("O tamanho do cofre mudou durante a leitura.");
            }
            complete = true;
            return data;
        } finally {
            if (!complete) Arrays.fill(data, (byte) 0);
        }
    }

    private static byte[] readChunks(InputStream source, int max) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        int length = 0;
        try {
            while (length < max) {
                byte[] chunk = new byte[Math.min(BUFFER_SIZE, max - length)];
                chunks.add(chunk);
                int used = readUpTo(source, chunk);
                length += used;
                if (used < chunk.length) break;
            }
            if (length == max && source.read() != -1) throw new IOException("Arquivo excede o limite de tamanho.");
            byte[] data = new byte[length];
            int offset = 0;
            for (byte[] chunk : chunks) {
                int count = Math.min(chunk.length, length - offset);
                System.arraycopy(chunk, 0, data, offset, count);
                offset += count;
            }
            return data;
        } finally {
            for (byte[] chunk : chunks) Arrays.fill(chunk, (byte) 0);
        }
    }

    private static int readUpTo(InputStream source, byte[] buffer) throws IOException {
        int filled = 0;
        while (filled < buffer.length) {
            int count = source.read(buffer, filled, buffer.length - filled);
            if (count == -1) break;
            if (count == 0) {
                int next = source.read();
                if (next == -1) break;
                buffer[filled++] = (byte) next;
            } else {
                filled += count;
            }
        }
        return filled;
    }

    static void copy(InputStream source, OutputStream destination, int max) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            int total = 0;
            while (total < max) {
                int count = readUpTo(source, buffer);
                if (count > max - total) throw new IOException("Arquivo excede o limite de tamanho.");
                destination.write(buffer, 0, count);
                total += count;
                if (count < buffer.length) return;
            }
            if (source.read() != -1) throw new IOException("Arquivo excede o limite de tamanho.");
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    static void writeAtomically(File destination, byte[] data, Runnable beforeCommit) throws IOException {
        File staging = new File(destination.getParentFile(), "private.arca.pending");
        boolean committed = false;
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(data);
                output.getFD().sync();
            }
            beforeCommit.run();
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            committed = true;
        } finally {
            if (!committed) staging.delete();
        }
    }
}
