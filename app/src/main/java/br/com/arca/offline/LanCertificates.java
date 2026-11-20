package br.com.arca.offline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;

import javax.security.auth.x500.X500Principal;

/** Issues the two X.509 profiles used by the local HTTPS server. */
final class LanCertificates {
    private static final byte[][] PRIVATE_IPV4_RANGES = {
            {10, 0, 0, 0, (byte) 255, 0, 0, 0},
            {(byte) 172, 16, 0, 0, (byte) 255, (byte) 240, 0, 0},
            {(byte) 192, (byte) 168, 0, 0, (byte) 255, (byte) 255, 0, 0}
    };
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] ECDSA_SHA256 = sequence(oid(1, 2, 840, 10045, 4, 3, 2));
    private static final byte[] CRITICAL = element(0x01, new byte[]{(byte) 0xff});
    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("uuMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter GENERALIZED_TIME =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private LanCertificates() {}

    static boolean isPrivateAddress(Inet4Address address) {
        if (address == null) return false;
        byte[] ip = address.getAddress();
        for (byte[] range : PRIVATE_IPV4_RANGES) {
            int mismatch = 0;
            for (int i = 0; i < ip.length; i++) mismatch |= (range[i] ^ ip[i]) & range[i + ip.length];
            if (mismatch == 0) return true;
        }
        return false;
    }

    static X509Certificate authority(PublicKey publicKey, PrivateKey signingKey, Instant now)
            throws GeneralSecurityException {
        byte[] keyId = keyIdentifier(publicKey);
        X500Principal name = new X500Principal("CN=Arca Offline LAN " + hex(keyId, 6, false));
        return issue(name, name, now.minus(7, ChronoUnit.DAYS), now.plus(3_650, ChronoUnit.DAYS),
                publicKey, signingKey,
                extension(19, true, sequence(CRITICAL, integer(BigInteger.ZERO))),
                extension(15, true, element(0x03, new byte[]{2, 0x04})),
                extension(14, false, element(0x04, keyId)),
                extension(30, true, nameConstraints()));
    }

    static X509Certificate server(Inet4Address ip, PublicKey publicKey, X509Certificate authority,
            PrivateKey signingKey, Instant now) throws GeneralSecurityException {
        if (!isPrivateAddress(ip)) throw new GeneralSecurityException("Endereço privado IPv4 obrigatório.");
        X500Principal name = new X500Principal("CN=Arca Offline LAN");
        Instant first = now.minus(1, ChronoUnit.DAYS);
        Instant last = now.plus(30, ChronoUnit.DAYS);
        Instant caFirst = authority.getNotBefore().toInstant();
        Instant caLast = authority.getNotAfter().toInstant();
        return issue(authority.getSubjectX500Principal(), name,
                first.isBefore(caFirst) ? caFirst : first, last.isAfter(caLast) ? caLast : last,
                publicKey, signingKey,
                extension(19, true, sequence()),
                extension(15, true, element(0x03, new byte[]{7, (byte) 0x80})),
                extension(17, false, sequence(element(0x87, ip.getAddress()))),
                extension(37, false, sequence(oid(1, 3, 6, 1, 5, 5, 7, 3, 1))),
                extension(35, false, sequence(element(0x80, keyIdentifier(authority.getPublicKey())))));
    }

    private static byte[] nameConstraints() {
        // Equal permitted/excluded DNS subtrees disallow DNS without requiring an empty dNSName.
        byte[] dns = sequence(element(0x82, "invalid".getBytes(StandardCharsets.US_ASCII)));
        byte[] allowed = element(0xa0, join(
                sequence(element(0x87, PRIVATE_IPV4_RANGES[0])),
                sequence(element(0x87, PRIVATE_IPV4_RANGES[1])),
                sequence(element(0x87, PRIVATE_IPV4_RANGES[2])),
                dns));
        byte[] excluded = element(0xa1, join(sequence(element(0x87, new byte[32])), dns));
        return sequence(allowed, excluded);
    }

    private static X509Certificate issue(X500Principal issuer, X500Principal subject,
            Instant first, Instant last, PublicKey publicKey, PrivateKey signingKey,
            byte[]... extensions) throws GeneralSecurityException {
        byte[] tbs = sequence(element(0xa0, integer(BigInteger.valueOf(2))),
                integer(new BigInteger(159, RANDOM).setBit(158)), ECDSA_SHA256, issuer.getEncoded(),
                sequence(time(first), time(last)), subject.getEncoded(), publicKey.getEncoded(),
                element(0xa3, sequence(extensions)));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(signingKey);
        signer.update(tbs);
        return decode(sequence(tbs, ECDSA_SHA256, element(0x03, join(new byte[]{0}, signer.sign()))));
    }

    static X509Certificate decode(byte[] der) throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    static String pem(X509Certificate certificate) throws CertificateException {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
    }

    static String fingerprint(X509Certificate certificate) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        return hex(digest, digest.length, true);
    }

    private static byte[] keyIdentifier(PublicKey key) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
    }

    private static String hex(byte[] bytes, int length, boolean delimited) {
        StringBuilder result = new StringBuilder(length * (delimited ? 3 : 2));
        for (int i = 0; i < length; i++) {
            if (i != 0 && delimited) result.append(':');
            int value = bytes[i] & 0xff;
            result.append(HEX[value >>> 4]).append(HEX[value & 0x0f]);
        }
        return result.toString();
    }

    private static byte[] time(Instant instant) {
        int year = instant.atOffset(ZoneOffset.UTC).getYear();
        boolean utc = year >= 1950 && year <= 2049;
        String formatted = (utc ? UTC_TIME : GENERALIZED_TIME).format(instant);
        return element(utc ? 0x17 : 0x18, formatted.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] extension(int id, boolean critical, byte[] value) {
        byte[] name = oid(2, 5, 29, id);
        byte[] octets = element(0x04, value);
        return critical ? sequence(name, CRITICAL, octets) : sequence(name, octets);
    }

    private static byte[] sequence(byte[]... values) {
        return element(0x30, join(values));
    }

    private static byte[] integer(BigInteger value) {
        return element(0x02, value.toByteArray());
    }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        writeArc(value, arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) writeArc(value, arcs[i]);
        return element(0x06, value.toByteArray());
    }

    private static void writeArc(ByteArrayOutputStream output, int arc) {
        int shift = (31 - Integer.numberOfLeadingZeros(arc)) / 7 * 7;
        for (; shift > 0; shift -= 7) output.write(0x80 | ((arc >>> shift) & 0x7f));
        output.write(arc & 0x7f);
    }

    private static byte[] element(int tag, byte[] value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(tag);
        if (value.length < 128) {
            output.write(value.length);
        } else {
            int width = (Integer.SIZE - Integer.numberOfLeadingZeros(value.length) + 7) / 8;
            output.write(0x80 | width);
            for (int i = width - 1; i >= 0; i--) output.write((value.length >>> (8 * i)) & 0xff);
        }
        output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static byte[] join(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }
}
