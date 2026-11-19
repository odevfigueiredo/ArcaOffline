import br.com.arca.offline.CryptoBox;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
public class MakeExample {
  public static void main(String[] args) throws Exception {
    String json = "{\"version\":1,\"records\":["
      + "{\"id\":\"exemplo-1\",\"title\":\"Checklist de documentos\",\"category\":\"Documento\",\"notes\":\"Registro demonstrativo. Cadastre cópias dos documentos de sua família com consentimento.\",\"phone\":\"\",\"date\":\"\",\"attachmentData\":\"\",\"attachmentName\":\"\",\"attachmentMime\":\"\"},"
      + "{\"id\":\"exemplo-2\",\"title\":\"Ponto de encontro (exemplo)\",\"category\":\"Emergência\",\"notes\":\"Escolha e registre um ponto de encontro real com sua família.\",\"phone\":\"\",\"date\":\"\",\"attachmentData\":\"\",\"attachmentName\":\"\",\"attachmentMime\":\"\"}]}";
    char[] pass = "ExemploArca2026!".toCharArray();
    byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
    CryptoBox.Session session = CryptoBox.newSession(pass);
    try {
      byte[] file = CryptoBox.seal(plaintext, session);
      CryptoBox.Session restored = CryptoBox.open(file, pass);
      byte[] recovered = restored.takePlaintext();
      try {
        if (!Arrays.equals(plaintext, recovered)) {
          throw new IllegalStateException("Arquivo exemplo não validado");
        }
        Files.write(Path.of(args[0]), file);
      } finally {
        Arrays.fill(recovered, (byte) 0);
        restored.clear();
      }
    } finally {
      Arrays.fill(plaintext, (byte) 0);
      Arrays.fill(pass, '\0');
      session.clear();
    }
    System.out.println("PASS: backup demonstrativo .arca gerado e restaurado");
  }
}
