package br.com.arca.offline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

public final class VaultStorageTest {
    public static void main(String[] args) throws Exception {
        byte[] exact = new byte[32 * 1024 + 56];
        for (int i = 0; i < exact.length; i++) exact[i] = (byte) i;
        check(Arrays.equals(exact, VaultStorage.read(new ByteArrayInputStream(exact), exact.length)), "leitura no limite");
        check(Arrays.equals(exact, VaultStorage.read(new ByteArrayInputStream(exact), exact.length + 64)), "bloco final parcial");
        expectIo(() -> VaultStorage.read(new ByteArrayInputStream(exact), exact.length - 1));
        expectIo(() -> VaultStorage.read(null, exact.length));

        var copied = new ByteArrayOutputStream();
        VaultStorage.copy(new ByteArrayInputStream(exact), copied, exact.length);
        check(Arrays.equals(exact, copied.toByteArray()), "cópia no limite");
        expectIo(() -> VaultStorage.copy(new ByteArrayInputStream(exact), copied, exact.length - 1));

        Path directory = Files.createTempDirectory("arca-storage-test-");
        Path vault = directory.resolve("private.arca");
        Path staging = directory.resolve("private.arca.pending");
        byte[] original = "Cofre original".getBytes(StandardCharsets.UTF_8);
        try {
            VaultStorage.writeAtomically(vault.toFile(), original, () -> {});
            try {
                VaultStorage.writeAtomically(vault.toFile(), exact, () -> { throw new CancellationException(); });
                throw new AssertionError("gravação cancelada foi confirmada");
            } catch (CancellationException expected) {
                check(Arrays.equals(original, Files.readAllBytes(vault)), "cofre original após falha");
                check(!Files.exists(staging), "arquivo temporário removido após falha");
            }
            VaultStorage.writeAtomically(vault.toFile(), exact, () -> {});
            check(Arrays.equals(exact, VaultStorage.readPrivate(vault, exact.length)), "substituição atômica e leitura privada");
            check(!Files.exists(staging), "arquivo temporário removido após sucesso");
            expectIo(() -> VaultStorage.readPrivate(vault, exact.length - 1));
        } finally {
            Files.deleteIfExists(staging);
            Files.deleteIfExists(vault);
            Files.delete(directory);
        }
        System.out.println("PASS: limites de leitura/cópia, substituição atômica e preservação do cofre quando a gravação é cancelada");
    }

    private interface IoOperation { void run() throws IOException; }

    private static void expectIo(IoOperation work) throws IOException {
        try {
            work.run();
            throw new AssertionError("entrada inválida foi aceita");
        } catch (IOException expected) {
            // O limite e os fluxos indisponíveis são erros de entrada/saída.
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
