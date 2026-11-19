package br.com.arca.offline;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Portable encrypted archive, version 1. No Android dependencies; unit-testable on a JVM.
 * Layout: MAGIC(8) || ITERATIONS(4, big endian) || SALT(16) || IV(12)
 *         || AES-256-GCM ciphertext+128-bit tag.
 * The entire header is authenticated as associated data (AAD).
 */
public final class CryptoBox {
    private CryptoBox() {}
    private static final byte[] MAGIC = "ARCAOF01".getBytes(StandardCharsets.US_ASCII);
    public static final int ITERATIONS = 600_000;
    private static final int MIN_READ_ITERATIONS = 210_000;
    private static final int MAX_READ_ITERATIONS = 2_000_000;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int HEADER_LEN = MAGIC.length + Integer.BYTES + SALT_LEN + IV_LEN;
    private static final int TAG_BITS = 128;
    private static final int ARCHIVE_OVERHEAD = HEADER_LEN + TAG_BITS / Byte.SIZE;
    public static final int MAX_PLAINTEXT_BYTES = 32 * 1024 * 1024;
    public static final int MAX_ARCHIVE_BYTES = MAX_PLAINTEXT_BYTES + ARCHIVE_OVERHEAD;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class Session {
        /** @deprecated Use {@link #takePlaintext()} and clear the returned bytes after use. */
        @Deprecated public byte[] plaintext;
        private final byte[] salt;
        private final byte[] keyMaterial;
        private final int iterations;
        private boolean cleared;

        private Session(byte[] plain, byte[] salt, byte[] keyMaterial, int iterations) {
            this.plaintext = plain;
            this.salt = salt;
            this.keyMaterial = keyMaterial;
            this.iterations = iterations;
        }

        /** Transfers the decrypted bytes to the caller, who must clear them after parsing. */
        public synchronized byte[] takePlaintext() {
            byte[] result = plaintext;
            plaintext = null;
            return result;
        }

        public int iterations() {
            return iterations;
        }

        public synchronized void clear() {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
                plaintext = null;
            }
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(keyMaterial, (byte) 0);
            cleared = true;
        }
    }

    public static Session newSession(char[] password) throws GeneralSecurityException {
        return newSession(password, null);
    }

    /** @deprecated Create a key-only session with {@link #newSession(char[])}. */
    @Deprecated
    public static Session newSession(char[] password, byte[] plain) throws GeneralSecurityException {
        if (password == null || password.length < 10) {
            throw new GeneralSecurityException("Senha precisa de 10 ou mais caracteres");
        }
        if (plain != null) validatePlaintext(plain);
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        return new Session(plain, salt, key, ITERATIONS);
    }

    private static byte[] derive(char[] password, byte[] salt, int iters) throws GeneralSecurityException {
        PBEKeySpec pbe = new PBEKeySpec(password, salt, iters, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(pbe).getEncoded();
        } finally {
            pbe.clearPassword();
        }
    }

    public static byte[] seal(byte[] plain, Session session) throws GeneralSecurityException {
        validatePlaintext(plain);
        if (session == null) throw new GeneralSecurityException("Cofre bloqueado");
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        byte[] header = new byte[HEADER_LEN];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        synchronized (session) {
            if (session.cleared) throw new GeneralSecurityException("Cofre bloqueado");
            ByteBuffer.wrap(header).put(MAGIC).putInt(session.iterations).put(session.salt).put(iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(session.keyMaterial, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
        }
        byte[] file = new byte[plain.length + ARCHIVE_OVERHEAD];
        System.arraycopy(header, 0, file, 0, HEADER_LEN);
        cipher.updateAAD(header);
        cipher.doFinal(plain, 0, plain.length, file, HEADER_LEN);
        return file;
    }

    private static void validatePlaintext(byte[] plain) throws GeneralSecurityException {
        if (plain == null || plain.length > MAX_PLAINTEXT_BYTES) {
            throw new GeneralSecurityException("Conteúdo ausente ou acima do limite de 32 MiB");
        }
    }

    public static Session open(byte[] file, char[] password) throws GeneralSecurityException {
        if (file == null || file.length < ARCHIVE_OVERHEAD || file.length > MAX_ARCHIVE_BYTES) {
            throw new GeneralSecurityException("Arquivo não é um backup Arca válido");
        }
        if (password == null) throw new GeneralSecurityException("Senha ausente");
        ByteBuffer buffer = ByteBuffer.wrap(file);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new GeneralSecurityException("Formato de arquivo inválido");
        int iterations = buffer.getInt();
        if (iterations < MIN_READ_ITERATIONS || iterations > MAX_READ_ITERATIONS)
            throw new GeneralSecurityException("Parâmetros criptográficos inválidos");
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        buffer.get(salt);
        buffer.get(iv);
        byte[] key = derive(password, salt, iterations);
        boolean opened = false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(file, 0, HEADER_LEN);
            byte[] plain = cipher.doFinal(file, HEADER_LEN, file.length - HEADER_LEN);
            Session session = new Session(plain, salt, key, iterations);
            opened = true;
            return session;
        } finally {
            if (!opened) Arrays.fill(key, (byte) 0);
        }
    }
}
