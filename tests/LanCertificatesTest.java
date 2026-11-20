package br.com.arca.offline;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class LanCertificatesTest {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair signer = generator.generateKeyPair();
        KeyPair tls = generator.generateKeyPair();
        Instant now = Instant.now();
        X509Certificate authority = LanCertificates.authority(signer.getPublic(), signer.getPrivate(), now);
        authority.verify(signer.getPublic());
        must(authority.getBasicConstraints() == 0 && authority.getKeyUsage()[5],
                "A CA local precisa limitar subautoridades.");
        must(authority.getCriticalExtensionOIDs().contains("2.5.29.30"),
                "Restrições de nomes ausentes.");
        String authoritySha = LanCertificates.fingerprint(authority);
        must(authoritySha.matches("[A-F0-9]{2}(:[A-F0-9]{2}){31}"), "Impressão SHA-256 inválida.");
        must(LanCertificates.fingerprint(LanCertificates.decode(authority.getEncoded())).equals(authoritySha),
                "Impressão da CA alterada após carregamento.");

        X509Certificate server = server("192.168.4.42", tls, authority, signer, now);
        X509Certificate renewed = server("172.31.22.11", tls, authority, signer, now);
        must(!LanCertificates.fingerprint(renewed).equals(LanCertificates.fingerprint(server)),
                "Uma nova folha deve ter nova impressão.");
        for (String rejected : new String[]{"0.0.0.0", "127.0.0.1", "169.254.0.1", "172.15.255.255",
                "172.32.0.0", "192.169.0.1", "8.8.8.8"}) {
            must(!LanCertificates.isPrivateAddress(ip(rejected)), "Endereço aceito: " + rejected);
            try {
                LanCertificates.server(ip(rejected), tls.getPublic(), authority, signer.getPrivate(), now);
                throw new AssertionError("Certificado emitido para IP fora de RFC 1918: " + rejected);
            } catch (GeneralSecurityException expected) {
                // Only the private LAN address may appear as the server's identity.
            }
        }
        server("10.0.0.42", tls, authority, signer, now);
        if (args.length != 0) {
            Path out = Path.of(args[0]);
            Files.writeString(out.resolve("arca-lan-test-ca.pem"), LanCertificates.pem(authority), StandardCharsets.US_ASCII);
            Files.writeString(out.resolve("arca-lan-test-leaf.pem"), LanCertificates.pem(server), StandardCharsets.US_ASCII);
        }
        System.out.println("PASS: CA local estável, certificados IP exatos, restrições de nome e validação PKIX");
    }

    private static X509Certificate server(String address, KeyPair key, X509Certificate authority,
            KeyPair signer, Instant now) throws Exception {
        X509Certificate leaf = LanCertificates.server(ip(address), key.getPublic(), authority, signer.getPrivate(), now);
        must(List.copyOf(leaf.getSubjectAlternativeNames()).equals(List.of(List.of(7, address))),
                "SAN precisa identificar somente o endereço IPv4 escolhido.");
        must(leaf.getExtendedKeyUsage().equals(List.of("1.3.6.1.5.5.7.3.1")) && leaf.getBasicConstraints() == -1,
                "Certificado folha precisa ser exclusivo para HTTPS.");
        PKIXParameters policy = new PKIXParameters(Set.of(new TrustAnchor(authority, null)));
        policy.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(
                CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), policy);
        return leaf;
    }

    private static Inet4Address ip(String text) throws Exception {
        return (Inet4Address) InetAddress.getByName(text);
    }

    private static void must(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
