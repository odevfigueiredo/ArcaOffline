package br.com.arca.offline;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/** The LAN server has a dedicated local authority and issues an exact-IP identity at startup. */
final class LanTls {
    private static final String KEY_STORE = "AndroidKeyStore";
    private static final String CA_ALIAS = "arca.offline.lan.ca.v1";
    private static final String SERVER_ALIAS = "arca.offline.lan.server.v1";

    private final SSLContext sslContext;
    private final byte[] certificateDer;
    private final String certificatePem;
    private final String certificateSha256;
    private final String serverCertificateSha256;

    private LanTls(SSLContext sslContext, X509Certificate authority, X509Certificate server)
            throws GeneralSecurityException {
        this.sslContext = sslContext;
        certificateDer = authority.getEncoded();
        certificatePem = LanCertificates.pem(authority);
        certificateSha256 = LanCertificates.fingerprint(authority);
        serverCertificateSha256 = LanCertificates.fingerprint(server);
    }

    static boolean isPrivateAddress(Inet4Address address) {
        return LanCertificates.isPrivateAddress(address);
    }

    static synchronized LanTls create(Context context, Inet4Address address)
            throws GeneralSecurityException, IOException {
        if (!isPrivateAddress(address)) throw new GeneralSecurityException("Endereço privado IPv4 obrigatório.");
        Instant now = Instant.now();
        KeyStore store = KeyStore.getInstance(KEY_STORE);
        store.load(null);
        KeyPair authorityKey = keyPair(store, CA_ALIAS);
        X509Certificate authority = authority(context, authorityKey, now);
        KeyPair serverKey = keyPair(store, SERVER_ALIAS);
        X509Certificate server = LanCertificates.server(address, serverKey.getPublic(), authority,
                authorityKey.getPrivate(), now);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(new KeyManager[]{new ServerKeyManager(serverKey.getPrivate(), server, authority)}, null, null);
        return new LanTls(sslContext, authority, server);
    }

    SSLContext context() { return sslContext; }
    byte[] certificateDer() { return certificateDer.clone(); }
    String certificatePem() { return certificatePem; }
    String certificateSha256() { return certificateSha256; }
    String serverCertificateSha256() { return serverCertificateSha256; }

    private static KeyPair keyPair(KeyStore store, String alias) throws GeneralSecurityException {
        if (store.containsAlias(alias)) {
            Key key = store.getKey(alias, null);
            Certificate certificate = store.getCertificate(alias);
            if (!(key instanceof PrivateKey) || certificate == null) {
                throw new GeneralSecurityException("Identidade HTTPS local inválida.");
            }
            return new KeyPair(certificate.getPublicKey(), (PrivateKey) key);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEY_STORE);
        KeyGenParameterSpec.Builder specification = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"));
        if (CA_ALIAS.equals(alias)) {
            specification.setDigests(KeyProperties.DIGEST_SHA256);
        } else {
            specification.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE);
        }
        generator.initialize(specification.build());
        return generator.generateKeyPair();
    }

    private static X509Certificate authority(Context context, KeyPair key, Instant now)
            throws GeneralSecurityException, IOException {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "arca-lan-ca-v1.cer"));
        X509Certificate existing = null;
        try {
            existing = LanCertificates.decode(file.readFully());
        } catch (FileNotFoundException missing) {
            // The public certificate is created once and reused across IP changes.
        }
        if (existing != null) {
            if (!Arrays.equals(existing.getPublicKey().getEncoded(), key.getPublic().getEncoded())
                    || existing.getBasicConstraints() != 0) {
                throw new GeneralSecurityException("Identidade HTTPS local inconsististente.");
            }
            existing.verify(key.getPublic());
            try {
                existing.checkValidity(Date.from(now));
                return existing;
            } catch (CertificateExpiredException expired) {
                // A renewed public authority has a new fingerprint, while the private key stays in the keystore.
            }
        }
        X509Certificate created = LanCertificates.authority(key.getPublic(), key.getPrivate(), now);
        byte[] contents = created.getEncoded();
        FileOutputStream output = file.startWrite();
        try {
            output.write(contents);
            output.getFD().sync();
        } catch (IOException failure) {
            file.failWrite(output);
            throw failure;
        }
        file.finishWrite(output);
        if (!Arrays.equals(contents, file.readFully())) {
            throw new IOException("Não foi possível salvar a identidade HTTPS local.");
        }
        return created;
    }

    private static final class ServerKeyManager extends X509ExtendedKeyManager {
        private final PrivateKey signingKey;
        private final X509Certificate[] chain;

        ServerKeyManager(PrivateKey key, X509Certificate server, X509Certificate authority) {
            signingKey = key;
            chain = new X509Certificate[]{server, authority};
        }

        private boolean supports(String keyType) {
            return "EC".equals(keyType) || "EC_EC".equals(keyType);
        }

        @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
            return supports(keyType) ? new String[]{SERVER_ALIAS} : null;
        }

        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return supports(keyType) ? SERVER_ALIAS : null;
        }

        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return supports(keyType) ? SERVER_ALIAS : null;
        }

        @Override public X509Certificate[] getCertificateChain(String alias) {
            return SERVER_ALIAS.equals(alias) ? chain.clone() : null;
        }

        @Override public PrivateKey getPrivateKey(String alias) {
            return SERVER_ALIAS.equals(alias) ? signingKey : null;
        }

        @Override public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            return null;
        }
    }
}
