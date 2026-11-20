package br.com.arca.offline;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Run on a disposable Android image. The fixture never overwrites a pre-existing vault. */
public final class ArcaSmokeInstrumentation extends Instrumentation {
    private static final String PASSWORD = "  Cofre Sintético 2026!  ";
    private static final String NEXT_PASSWORD = "Outra Senha Sintética 2027!";
    private static final String TITLE = "Saúde da família (sintético)";
    private static final String EDITED_TITLE = "Saúde da família atualizada";
    private static final String NOTE = "Informação fictícia do teste de ponta a ponta";
    private static final long DEADLINE_MS = 25_000L;

    private final List<Uri> fixtures = new ArrayList<>();
    private final List<String> passed = new ArrayList<>();
    private MainActivity activity;
    private Context context;
    private UiAutomation automation;
    private File vault;
    private boolean pdfOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        pdfOnly = arguments != null && "true".equals(arguments.getString("pdfOnly"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        try {
            context = getTargetContext();
            vault = new File(context.getFilesDir(), "private.arca");
            require(!vault.exists(), "Use uma instalação de teste vazia em emulador descartável.");

            require(Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) == 1, "Ative o modo avião no emulador de testes.");
            String[] permissions = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            require(permissions != null && permissions.length == 2
                    && Arrays.asList(permissions).containsAll(Arrays.asList(
                            android.Manifest.permission.INTERNET, android.Manifest.permission.ACCESS_NETWORK_STATE)),
                    "O APK declara permissões além do acesso web local.");
            automation = getUiAutomation();
            Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(launch);
            require(onMain(() -> (activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_SECURE) != 0), "Janela sem FLAG_SECURE.");
            passed("Modo avião, permissões restritas à rede local e janela protegida");
            if (pdfOnly) {
                byte[] sample = samplePdf();
                onMain(() -> PdfPreview.show(activity, "Teste de PDF", sample));
                verifyPdfPages();
                await(() -> {
                    for (byte value : sample) if (value != 0) return false;
                    return true;
                }, "limpeza dos bytes da prévia");
                passed("PDF renderizado em processo isolado, com paginação e descarte dos bytes");
                result.putString("stream", "\n" + String.join("\n", passed) + "\n");
                status = Activity.RESULT_OK;
                return;
            }
            require(Build.VERSION.SDK_INT >= 29, "O teste de URIs MediaStore requer Android 10+.");

            type(0, PASSWORD);
            type(1, PASSWORD);
            tap("Criar meu cofre");
            awaitText("Adicionar registro");
            require(vault.isFile(), "A criação não persistiu o cofre.");
            require(payload(Files.readAllBytes(vault.toPath()), PASSWORD).contains("\"records\""), "Cofre inicial inválido.");
            passed("Criação e criptografia no Android com espaços e caracteres UTF-8");

            tap("Adicionar registro");
            type(0, TITLE);
            type(1, NOTE);
            Uri pdf = fixture("arca-smoke.pdf", "application/pdf", samplePdf());
            pick(Intent.ACTION_OPEN_DOCUMENT, pdf, "Selecionar PDF");
            awaitText("arca-smoke.pdf");
            tap("Salvar registro criptografado");
            awaitText(TITLE);
            byte[] initial = Files.readAllBytes(vault.toPath());
            require(!new String(initial, StandardCharsets.UTF_8).contains(NOTE), "Nota em claro no armazenamento.");
            require(payload(initial, PASSWORD).contains(NOTE), "Nota ausente após descriptografar.");

            tap(TITLE);
            tap("Pré-visualizar anexo");
            verifyPdfPages();
            passed("Anexo importado por URI, salvo criptografado e PDF de duas páginas lido no app");

            tap("Editar registro");
            type(0, EDITED_TITLE);
            tap("Salvar registro criptografado");
            awaitText(EDITED_TITLE);
            type(0, "sem correspondência");
            awaitText("Nenhum registro encontrado");
            type(0, "saude");
            awaitText(EDITED_TITLE);
            type(0, "");
            passed("Edição de registro e busca sem acentos");

            Uri backup = fixture("arca-smoke.arca", "application/octet-stream", null);
            tap("Backup");
            pick(Intent.ACTION_CREATE_DOCUMENT, backup, "Exportar arquivo .arca");
            byte[] exported = awaitExport(backup);
            require(Arrays.equals(exported, Files.readAllBytes(vault.toPath())), "O backup diverge do arquivo privado.");
            require(payload(exported, PASSWORD).contains(EDITED_TITLE), "Registro editado ausente do backup.");
            goHome();
            tap("Bloquear cofre");
            awaitText("Desbloquear");
            type(0, "Senha de teste errada");
            tap("Desbloquear");
            awaitDialog("Não foi possível");
            tapDialog("Entendi");
            require(Arrays.equals(exported, Files.readAllBytes(vault.toPath())), "Falha de desbloqueio alterou o cofre.");
            type(0, PASSWORD);
            tap("Desbloquear");
            awaitText(EDITED_TITLE);
            passed("Backup confere com o armazenamento e senha incorreta preserva o cofre");

            tap("Segurança");
            tap("Alterar senha principal");
            awaitDialog("Trocar senha principal");
            typeDialog(0, NEXT_PASSWORD);
            typeDialog(1, NEXT_PASSWORD);
            tapDialog("Alterar");
            byte[] rotated = awaitRotation(exported);
            require(payload(rotated, NEXT_PASSWORD).contains(EDITED_TITLE), "Senha nova não abriu o cofre.");
            require(rejects(rotated, PASSWORD), "A senha antiga ainda abriu o cofre atualizado.");
            goHome();
            tap("Bloquear cofre");
            awaitText("Desbloquear");
            passed("Troca de senha recria o cofre sem invalidar o backup antigo");

            byte[] corrupt = exported.clone();
            corrupt[corrupt.length - 1] ^= 1;
            Uri bad = fixture("arca-smoke-adulterado.arca", "application/octet-stream", corrupt);
            pick(Intent.ACTION_OPEN_DOCUMENT, bad, "Restaurar backup criptografado");
            restorePassword(PASSWORD);
            awaitDialog("Restauração negada");
            tapDialog("Entendi");
            require(Arrays.equals(rotated, Files.readAllBytes(vault.toPath())), "Backup adulterado modificou o cofre.");
            Uri unsupported = fixture("arca-smoke-esquema.arca", "application/octet-stream", invalidSchema());
            pick(Intent.ACTION_OPEN_DOCUMENT, unsupported, "Restaurar backup criptografado");
            restorePassword(PASSWORD);
            awaitDialog("Restauração negada");
            tapDialog("Entendi");
            require(Arrays.equals(rotated, Files.readAllBytes(vault.toPath())), "Esquema inválido modificou o cofre.");
            pick(Intent.ACTION_OPEN_DOCUMENT, backup, "Restaurar backup criptografado");
            restorePassword(PASSWORD);
            awaitDialog("Substituir o cofre atual?");
            require(Arrays.equals(rotated, Files.readAllBytes(vault.toPath())), "O cofre mudou antes da confirmação.");
            tapDialog("Substituir cofre");
            awaitText(EDITED_TITLE);
            require(payload(Files.readAllBytes(vault.toPath()), PASSWORD).contains(EDITED_TITLE), "Backup antigo não foi restaurado.");
            passed("Backups adulterado e de esquema incompatível rejeitados; backup válido restaurado com confirmação");

            tap(EDITED_TITLE);
            tap("Excluir registro");
            tapDialog("Excluir");
            awaitText("Nenhum registro encontrado");
            require(!payload(Files.readAllBytes(vault.toPath()), PASSWORD).contains(EDITED_TITLE), "Exclusão não persistiu.");
            onMain(() -> {
                activity.recreate();
                return null;
            });
            awaitDialog("Desbloquear");
            passed("Exclusão persiste e recriação da Activity exige desbloqueio");

            result.putString("stream", "\n" + String.join("\n", passed) + "\n");
            status = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", "\n" + String.join("\n", passed) + "\nFALHOU: " + android.util.Log.getStackTraceString(failure) + "\n");
            result.putString("shortMsg", failure.toString());
        } finally {
            for (Uri uri : fixtures) context.getContentResolver().delete(uri, null, null);
            finish(status, result);
        }
    }

    private void verifyPdfPages() {
        awaitDialog("Página 1 de 2");
        tapDialog("Próxima");
        awaitDialog("Página 2 de 2");
        tapDialog("Anterior");
        awaitDialog("Página 1 de 2");
        tapDialog("Fechar");
    }

    private void passed(String label) {
        passed.add("PASSOU — " + label);
    }

    private static byte[] samplePdf() throws Exception {
        PdfDocument pdf = new PdfDocument();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 1; index <= 2; index++) {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(300, 420, index).create());
                Paint paint = new Paint();
                paint.setTextSize(26);
                page.getCanvas().drawText("Teste Arca " + index, 25, 70, paint);
                pdf.finishPage(page);
            }
            pdf.writeTo(output);
            return output.toByteArray();
        } finally {
            pdf.close();
        }
    }

    private Uri fixture(String name, String mime, byte[] bytes) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        require(uri != null, "MediaStore não criou " + name);
        fixtures.add(uri);
        try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            require(output != null, "MediaStore não abriu " + name);
            if (bytes != null) output.write(bytes);
        }
        return uri;
    }

    private void pick(String action, Uri uri, String button) {
        IntentFilter filter = new IntentFilter(action);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        try { filter.addDataType("*/*"); }
        catch (IntentFilter.MalformedMimeTypeException failure) { throw new AssertionError(failure); }
        ActivityMonitor monitor = addMonitor(filter, new ActivityResult(Activity.RESULT_OK, new Intent().setData(uri)), true);
        try {
            tap(button);
            await(() -> monitor.getHits() != 0, "Ação SAF " + action);
        } finally {
            removeMonitor(monitor);
        }
    }

    private byte[] awaitExport(Uri backup) throws Exception {
        long deadline = SystemClock.uptimeMillis() + DEADLINE_MS;
        do {
            try (InputStream input = context.getContentResolver().openInputStream(backup)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int size; (size = input.read(chunk)) != -1;) output.write(chunk, 0, size);
                byte[] bytes = output.toByteArray();
                if (bytes.length == vault.length() && bytes.length != 0) return bytes;
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Exportação não concluiu.");
    }

    private byte[] awaitRotation(byte[] before) throws Exception {
        long deadline = SystemClock.uptimeMillis() + DEADLINE_MS;
        do {
            byte[] bytes = Files.readAllBytes(vault.toPath());
            if (!Arrays.equals(bytes, before)) return bytes;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Troca de senha não gravou o novo cofre.");
    }

    private static String payload(byte[] ciphertext, String password) throws Exception {
        char[] chars = password.toCharArray();
        byte[] clear = null;
        CryptoBox.Session session = null;
        try {
            session = CryptoBox.open(ciphertext, chars);
            clear = session.takePlaintext();
            return new String(clear, StandardCharsets.UTF_8);
        } finally {
            if (session != null) session.clear();
            Arrays.fill(chars, '\0');
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    private static byte[] invalidSchema() throws Exception {
        CryptoBox.Session key = CryptoBox.newSession(PASSWORD.toCharArray());
        try {
            return CryptoBox.seal("{\"version\":99,\"records\":[]}".getBytes(StandardCharsets.UTF_8), key);
        } finally {
            key.clear();
        }
    }

    private static boolean rejects(byte[] ciphertext, String password) throws Exception {
        try {
            payload(ciphertext, password);
            return false;
        } catch (java.security.GeneralSecurityException expected) {
            return true;
        }
    }

    private void restorePassword(String password) {
        awaitDialog("Senha do backup");
        typeDialog(0, password);
        tapDialog("Validar e restaurar");
    }

    private void goHome() {
        await(() -> onMain(() -> {
            if (findView(activity.getWindow().getDecorView(), "Adicionar registro") != null) return true;
            activity.onBackPressed();
            return findView(activity.getWindow().getDecorView(), "Adicionar registro") != null;
        }), "volta ao início do cofre");
    }

    private void type(int index, String value) {
        onMain(() -> {
            List<EditText> edits = new ArrayList<>();
            findEdits(activity.getWindow().getDecorView(), edits);
            require(index < edits.size(), "Campo de texto ausente na posição " + index);
            edits.get(index).setText(value);
            return null;
        });
    }

    private static void findEdits(View view, List<EditText> edits) {
        if (view instanceof EditText) edits.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findEdits(group.getChildAt(i), edits);
        }
    }

    private void tap(String text) {
        await(() -> onMain(() -> {
            View view = findClickable(activity.getWindow().getDecorView(), text);
            return view != null && view.isEnabled() && view.performClick();
        }), "Botão " + text);
    }

    private void awaitText(String text) {
        await(() -> onMain(() -> findView(activity.getWindow().getDecorView(), text) != null), text);
    }

    private static View findView(View view, String text) {
        if (view instanceof TextView && !(view instanceof EditText)
                && ((TextView) view).getText().toString().contains(text)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findView(group.getChildAt(i), text);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static View findClickable(View view, String label) {
        if (matches(view, label)) {
            for (View candidate = view; candidate != null;
                    candidate = candidate.getParent() instanceof View ? (View) candidate.getParent() : null) {
                if (candidate.isClickable()) return candidate;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findClickable(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static boolean matches(View view, String text) {
        return view instanceof TextView && !(view instanceof EditText)
                && ((TextView) view).getText().toString().contains(text);
    }

    private void awaitDialog(String text) {
        await(() -> findNode(automation.getRootInActiveWindow(), text, false) != null, text);
    }

    private void tapDialog(String text) {
        await(() -> {
            AccessibilityNodeInfo node = findNode(automation.getRootInActiveWindow(), text, true);
            return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }, "Ação " + text);
    }

    private void typeDialog(int index, String value) {
        await(() -> {
            List<AccessibilityNodeInfo> edits = new ArrayList<>();
            findDialogEdits(automation.getRootInActiveWindow(), edits);
            if (index >= edits.size()) return false;
            Bundle parameters = new Bundle();
            parameters.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return edits.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, parameters);
        }, "Campo do diálogo na posição " + index);
    }

    private static void findDialogEdits(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> edits) {
        if (node == null) return;
        if (node.isEditable()) edits.add(node);
        for (int i = 0; i < node.getChildCount(); i++) findDialogEdits(node.getChild(i), edits);
    }

    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String text, boolean button) {
        if (node == null) return null;
        CharSequence actual = node.getText();
        if ((!button || node.isClickable()) && actual != null && actual.toString().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findNode(node.getChild(i), text, button);
            if (match != null) return match;
        }
        return null;
    }

    private static void await(Supplier<Boolean> condition, String description) {
        long deadline = SystemClock.uptimeMillis() + DEADLINE_MS;
        do {
            if (condition.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Tempo esgotado aguardando " + description);
    }

    private <T> T onMain(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try { result.set(action.get()); }
            catch (Throwable problem) { failure.set(problem); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
