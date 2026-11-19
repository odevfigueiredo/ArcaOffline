import br.com.arca.offline.CryptoBox;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public class CryptoBoxTest {
    private static final int IV_START = 8 + 4 + 16;
    private static final int IV_END = IV_START + 12;
    private static final char[] PASSWORD = " Frase forte: café, ação, 🔑 ".toCharArray();

    public static void main(String[] args) throws Exception {
        roundTripAndReseal();
        invalidArchives();
        changePassword();
        sessionCleanup();
        sizeBoundaries();
        openLegacyBackup(args.length == 0 ? Paths.get("examples", "backup-exemplo.arca") : Paths.get(args[0]));
        System.out.println("PASS: 6 grupos de testes do formato ARCAOF01");
    }

    private static void roundTripAndReseal() throws Exception {
        byte[] input = "Documento familiar confidencial\nçãé ✓".getBytes(StandardCharsets.UTF_8);
        CryptoBox.Session session = CryptoBox.newSession(PASSWORD);
        byte[] first = CryptoBox.seal(input, session);
        byte[] second = CryptoBox.seal(input, session);
        must(!sameRange(first, second, IV_START, IV_END), "IV repetido");
        must(session.iterations() == 600_000 && ByteBuffer.wrap(first).getInt(8) == 600_000,
                "Cofre novo não gravou 600.000 iterações");
        mustReject(() -> CryptoBox.open(first, "Frase forte: café, ação, 🔑".toCharArray()),
                "senha com os espaços removidos");
        CryptoBox.Session opened = CryptoBox.open(first, PASSWORD);
        byte[] restored = opened.takePlaintext();
        must(Arrays.equals(input, restored), "Conteúdo alterado no round trip");
        Arrays.fill(restored, (byte) 0);
        byte[] update = "outro conteúdo".getBytes(StandardCharsets.UTF_8);
        byte[] resealed = CryptoBox.seal(update, opened);
        must(Arrays.equals(update, readPlaintext(resealed, PASSWORD)), "Falhou regravação");
        opened.clear();
        session.clear();
    }

    private static void invalidArchives() throws Exception {
        CryptoBox.Session session = CryptoBox.newSession(PASSWORD);
        byte[] archive = CryptoBox.seal(new byte[] {1, 2, 3}, session);
        session.clear();
        mustReject(() -> CryptoBox.open(archive, "senha-errada".toCharArray()), "senha errada");
        mustReject(() -> CryptoBox.open(archive, null), "senha ausente");
        mustReject(() -> CryptoBox.open(null, PASSWORD), "arquivo ausente");
        mustReject(() -> CryptoBox.open(Arrays.copyOf(archive, 8), PASSWORD), "cabeçalho truncado");
        mustReject(() -> CryptoBox.open(Arrays.copyOf(archive, archive.length - 1), PASSWORD), "tag truncada");
        mustReject(() -> CryptoBox.open(Arrays.copyOf(archive, archive.length + 1), PASSWORD), "bytes depois da tag");
        assertModifiedArchiveRejected(archive, 0, "formato desconhecido");
        assertModifiedArchiveRejected(archive, 11, "iterações adulteradas");
        assertModifiedArchiveRejected(archive, 19, "salt adulterado");
        assertModifiedArchiveRejected(archive, IV_START, "IV adulterado");
        assertModifiedArchiveRejected(archive, IV_END, "conteúdo adulterado");
        assertModifiedArchiveRejected(archive, archive.length - 1, "tag adulterada");
        byte[] invalidIterations = archive.clone();
        ByteBuffer.wrap(invalidIterations).putInt(8, Integer.MAX_VALUE);
        mustReject(() -> CryptoBox.open(invalidIterations, PASSWORD), "iterações acima do teto");
        ByteBuffer.wrap(invalidIterations).putInt(8, 0);
        mustReject(() -> CryptoBox.open(invalidIterations, PASSWORD), "iterações abaixo do piso");
    }

    private static void changePassword() throws Exception {
        byte[] input = {4, 7, 12, 22};
        char[] newPassword = "Uma outra senha municipal".toCharArray();
        CryptoBox.Session previousSession = CryptoBox.newSession(PASSWORD);
        CryptoBox.Session updatedSession = CryptoBox.newSession(newPassword);
        byte[] before = CryptoBox.seal(input, previousSession);
        byte[] after = CryptoBox.seal(input, updatedSession);
        previousSession.clear();
        updatedSession.clear();
        must(!sameRange(before, after, 12, IV_START), "Salt repetido na troca de senha");
        mustReject(() -> CryptoBox.open(after, PASSWORD), "senha antiga no novo cofre");
        must(Arrays.equals(input, readPlaintext(before, PASSWORD)), "Backup antigo ficou inacessível");
        must(Arrays.equals(input, readPlaintext(after, newPassword)), "Nova senha falhou");
        Arrays.fill(newPassword, '\0');
    }

    @SuppressWarnings("deprecation")
    private static void sessionCleanup() throws Exception {
        byte[] input = {3, 2, 1};
        CryptoBox.Session session = CryptoBox.newSession(PASSWORD);
        must(session.takePlaintext() == null, "Nova chave não deveria reter conteúdo");
        byte[] archive = CryptoBox.seal(input, session);
        CryptoBox.Session opened = CryptoBox.open(archive, PASSWORD);
        byte[] restored = opened.takePlaintext();
        must(opened.takePlaintext() == null, "Conteúdo aberto deve ser retirado uma única vez");
        opened.clear();
        must(Arrays.equals(input, restored), "Conteúdo transferido é responsabilidade do chamador");
        Arrays.fill(restored, (byte) 0);
        CryptoBox.Session abandoned = CryptoBox.open(archive, PASSWORD);
        byte[] unread = abandoned.plaintext;
        abandoned.clear();
        must(Arrays.equals(new byte[input.length], unread), "Sessão encerrada reteve conteúdo recebido");
        must(abandoned.takePlaintext() == null, "Sessão encerrada devolveu conteúdo");
        session.clear();
        session.clear();
        mustReject(() -> CryptoBox.seal(input, session), "sessão encerrada");
        mustReject(() -> CryptoBox.seal(input, null), "sessão ausente");
        mustReject(() -> CryptoBox.newSession("curta".toCharArray()), "senha nova curta");
    }

    private static void sizeBoundaries() throws Exception {
        CryptoBox.Session session = CryptoBox.newSession(PASSWORD);
        byte[] empty = CryptoBox.seal(new byte[0], session);
        must(empty.length == 56, "Tamanho inesperado do cabeçalho e da tag");
        must(readPlaintext(empty, PASSWORD).length == 0, "Conteúdo vazio não abriu");
        byte[] largestAllowed = new byte[CryptoBox.MAX_PLAINTEXT_BYTES];
        largestAllowed[0] = 42;
        largestAllowed[largestAllowed.length - 1] = 93;
        byte[] sealed = CryptoBox.seal(largestAllowed, session);
        must(sealed.length == CryptoBox.MAX_ARCHIVE_BYTES, "Limite do arquivo inconsistente");
        must(Arrays.equals(largestAllowed, readPlaintext(sealed, PASSWORD)), "Maior arquivo permitido não abriu");
        mustReject(() -> CryptoBox.seal(null, session), "conteúdo ausente");
        mustReject(() -> CryptoBox.seal(new byte[CryptoBox.MAX_PLAINTEXT_BYTES + 1], session), "conteúdo acima do limite");
        mustReject(() -> CryptoBox.open(new byte[CryptoBox.MAX_ARCHIVE_BYTES + 1], PASSWORD), "arquivo acima do limite");
        session.clear();
    }

    private static void openLegacyBackup(Path path) throws Exception {
        char[] examplePassword = "ExemploArca2026!".toCharArray();
        byte[] archive = Files.readAllBytes(path);
        CryptoBox.Session legacy = CryptoBox.open(archive, examplePassword);
        byte[] plaintext = legacy.takePlaintext();
        try {
            String json = new String(plaintext, StandardCharsets.UTF_8);
            must(json.contains("\"version\":1") && json.contains("exemplo-1") && json.contains("Ponto de encontro"),
                    "Backup de exemplo da versão original não foi restaurado");
            must(legacy.iterations() == 310_000, "Contagem histórica não foi lida do cabeçalho");
            byte[] resealed = CryptoBox.seal(plaintext, legacy);
            must(ByteBuffer.wrap(resealed).getInt(8) == 310_000, "A regravação alterou o custo do backup existente");
            must(Arrays.equals(plaintext, readPlaintext(resealed, examplePassword)), "A regravação perdeu dados");

            char[] newPassword = "Nova senha para backup antigo".toCharArray();
            CryptoBox.Session upgraded = CryptoBox.newSession(newPassword);
            try {
                byte[] rekeyed = CryptoBox.seal(plaintext, upgraded);
                must(ByteBuffer.wrap(rekeyed).getInt(8) == 600_000, "A troca de senha não atualizou o custo");
                must(Arrays.equals(plaintext, readPlaintext(rekeyed, newPassword)), "A atualização perdeu dados antigos");
            } finally {
                Arrays.fill(newPassword, '\0');
                upgraded.clear();
            }
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(examplePassword, '\0');
            legacy.clear();
        }
    }

    private static boolean sameRange(byte[] first, byte[] second, int from, int to) {
        return Arrays.equals(Arrays.copyOfRange(first, from, to), Arrays.copyOfRange(second, from, to));
    }

    private static byte[] readPlaintext(byte[] archive, char[] password) throws GeneralSecurityException {
        CryptoBox.Session session = CryptoBox.open(archive, password);
        try {
            return session.takePlaintext();
        } finally {
            session.clear();
        }
    }

    private static void assertModifiedArchiveRejected(byte[] archive, int position, String scenario) throws Exception {
        byte[] modified = archive.clone();
        modified[position] ^= 0x21;
        mustReject(() -> CryptoBox.open(modified, PASSWORD), scenario);
    }

    private static void mustReject(SecurityOperation operation, String scenario) throws Exception {
        try {
            operation.run();
        } catch (GeneralSecurityException expected) {
            return;
        }
        throw new AssertionError("O cofre aceitou " + scenario);
    }

    private static void must(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    interface SecurityOperation {
        void run() throws GeneralSecurityException;
    }
}
