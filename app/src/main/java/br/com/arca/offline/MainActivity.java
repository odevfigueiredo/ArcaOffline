package br.com.arca.offline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.NumberFormat;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(11, 15, 22);
    private static final int PANEL = Color.rgb(23, 29, 40);
    private static final int PANEL2 = Color.rgb(32, 39, 52);
    private static final int WHITE = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(169, 179, 194);
    private static final int RED = Color.rgb(244, 108, 119);
    private static final int PRIMARY = Color.rgb(167, 41, 55);
    private static final String ALL_CATEGORIES = "Todas";
    private static final int REQ_ATTACH = 101;
    private static final int REQ_BACKUP_EXPORT = 102;
    private static final int REQ_BACKUP_IMPORT = 103;
    private static final int REQ_ATTACHMENT_EXPORT = 104;
    private static final int REQ_LAN_CERTIFICATE = 105;
    private static final long PICKER_TIMEOUT_MS = 60_000;
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern MIME_TYPE = Pattern.compile("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+");
    private static final Pattern FILE_NAME_DISALLOWED = Pattern.compile("[\\p{Cc}\\p{Cf}/\\\\]");
    private static final ExecutorService STORAGE_WORKER = Executors.newSingleThreadExecutor();
    private static final ExecutorService DOCUMENT_WORKER = Executors.newFixedThreadPool(2);
    private File storeFile;
    private CryptoBox.Session session;
    private JSONObject vault;
    private LinearLayout screen;
    private LinearLayout recordList;
    private String filter = ALL_CATEGORIES;
    private String query = "";
    private int pendingPicker;
    private int pickerRequestCode;
    private int nextRequest = 100;
    private int expiredRequest;
    private long pickerDeadline;
    private boolean started;
    private volatile int generation;
    private boolean busy;
    private final Handler lockHandler = new Handler(Looper.getMainLooper());
    private final Runnable delayedLock = this::expirePicker;
    private String currentPage = "locked";
    private AlertDialog activeDialog;

    private LanLocation lanLocation;
    private LanTls lanIdentity;
    private LanServer lanServer;
    private LanVault lanVault;
    private boolean lanStarting;
    private TextView lanStatus;
    private final Runnable monitorLan = this::updateLanStatus;

    // Draft data kept only while the editor is open.
    private EditText formTitle, formNotes, formPhone, formDate;
    private Spinner formCategory;
    private TextView formAttachmentInfo;
    private String draftAttachmentBase64 = null;
    private String draftAttachmentName = null;
    private String draftAttachmentMime = null;
    private BooleanSupplier unsavedChanges;
    private JSONObject pendingAttachment;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        storeFile = new File(getFilesDir(), "private.arca");
        showLocked();
    }

    @Override protected void onPause() {
        if (lanServer != null || lanStarting) lockAndShow();
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        if (pendingPicker == 0) {
            lockAndShow();
        } else if (SystemClock.elapsedRealtime() >= pickerDeadline) {
            expirePicker();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (pendingPicker != 0 && SystemClock.elapsedRealtime() >= pickerDeadline) expirePicker();
    }

    @Override protected void onDestroy() {
        clearSession();
        super.onDestroy();
    }

    private void expirePicker() {
        if (pendingPicker == 0) return;
        expiredRequest = pickerRequestCode;
        lockAndShow();
        if (started) toast("O tempo de seleção terminou. Inicie a operação novamente.");
    }

    private void clearSession() {
        generation++;
        disconnectLan();
        lanLocation = null;
        lanIdentity = null;
        busy = false;
        lockHandler.removeCallbacks(delayedLock);
        pendingPicker = 0;
        pickerRequestCode = 0;
        pendingAttachment = null;
        dismissDialog();
        CryptoBox.Session previous = session;
        session = null;
        if (previous != null) previous.clear();
        vault = null;
        recordList = null;
        query = "";
        filter = ALL_CATEGORIES;
        clearDraft();
    }

    private void lockAndShow() {
        clearSession();
        showLocked();
    }

    private void clearDraft() {
        formTitle = null;
        formNotes = null;
        formPhone = null;
        formDate = null;
        formCategory = null;
        formAttachmentInfo = null;
        draftAttachmentBase64 = null;
        draftAttachmentName = null;
        draftAttachmentMime = null;
        unsavedChanges = null;
    }

    private int dp(float n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private GradientDrawable border(int color, int border, int radius) {
        GradientDrawable d = bg(color, radius); d.setStroke(dp(1), border); return d;
    }
    private TextView label(String t, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(t); v.setTextColor(color); v.setTextSize(size);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setLineSpacing(dp(2), 1f); return v;
    }
    private void space(LinearLayout box, int h) { View v = new View(this); box.addView(v, lp(1,h)); }
    private void text(LinearLayout parent, String text, int size, int color, boolean bold) {
        parent.addView(label(text, size, color, bold));
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout panel(LinearLayout parent) {
        LinearLayout c = column(); c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(bg(PANEL, 16));
        LinearLayout.LayoutParams p = lp(-1, -2); p.bottomMargin = dp(12); parent.addView(c,p); return c;
    }
    private void button(LinearLayout parent, String text, boolean primary, View.OnClickListener click) {
        Button b = new Button(this); b.setText(text); b.setTextColor(WHITE); b.setTextSize(15);
        b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(13),dp(14),dp(13),dp(14));
        b.setBackground(bg(primary ? PRIMARY : PANEL2, 12));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = lp(-1, -2); p.bottomMargin = dp(9); parent.addView(b,p);
    }
    private EditText input(LinearLayout parent, String title, String hint, String value, boolean multiline) {
        text(parent,title,13,MUTED,true); space(parent,6);
        EditText e = new EditText(this); e.setTextColor(WHITE); e.setHintTextColor(MUTED);
        e.setTextSize(15); e.setSingleLine(!multiline);
        e.setSaveEnabled(false); e.setImeOptions(e.getImeOptions() | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        if (multiline) { e.setMinLines(3); e.setGravity(Gravity.TOP); }
        e.setHint(hint); e.setText(value == null ? "" : value); e.setPadding(dp(13),dp(12),dp(13),dp(12));
        e.setBackground(border(PANEL2,Color.rgb(59,67,80),10));
        LinearLayout.LayoutParams p = lp(-1,-2); p.bottomMargin=dp(15); parent.addView(e,p); return e;
    }
    private EditText password(LinearLayout parent, String title, boolean newPassword) {
        EditText e = input(parent,title,newPassword ? "No mínimo 10 caracteres" : "", "", false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
    private static char[] takePassword(EditText input) {
        Editable text = input.getText();
        char[] password = new char[text.length()];
        text.getChars(0, text.length(), password, 0);
        text.clear();
        return password;
    }
    private String value(EditText e) { return e.getText().toString().trim(); }
    private void toast(String msg) { Toast.makeText(this,msg,Toast.LENGTH_LONG).show(); }
    private void error(String title, String message) {
        showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Entendi",null));
    }
    private AlertDialog showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        trackDialog(dialog);
        return dialog;
    }
    private void trackDialog(AlertDialog dialog) {
        dismissDialog();
        activeDialog = dialog;
    }
    private void dismissDialog() {
        AlertDialog previous = activeDialog;
        activeDialog = null;
        if (previous != null) previous.dismiss();
    }
    private LinearLayout begin(String subtitle, String title, boolean back) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        scroll.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
        screen = column(); screen.setPadding(dp(20),dp(19),dp(20),dp(28));
        scroll.addView(screen);
        setContentView(scroll);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        if (back) {
            Button b = new Button(this); b.setText("‹"); b.setContentDescription("Voltar");
            b.setTextSize(30); b.setTextColor(RED); b.setBackgroundColor(Color.TRANSPARENT);
            b.setOnClickListener(v -> leavePage()); top.addView(b, lp(48,48));
        }
        LinearLayout names = column(); text(names,subtitle,12,RED,true); text(names,title,25,WHITE,true);
        top.addView(names,new LinearLayout.LayoutParams(0,-2,1)); screen.addView(top);
        space(screen,22);
        return screen;
    }

    private JSONArray records() { return vault.optJSONArray("records"); }

    private interface Work<T> { T run(int token) throws Exception; }

    private <T> void runWork(String title, Work<T> work, Consumer<T> success, Consumer<Exception> failure) {
        runWork(title, work, success, failure, discarded -> {});
    }

    private <T> void runWork(String title, Work<T> work, Consumer<T> success, Consumer<Exception> failure, Consumer<T> discard) {
        runWork(STORAGE_WORKER, title, work, success, failure, discard);
    }

    private <T> void runDocumentWork(String title, Work<T> work, Consumer<T> success, Consumer<Exception> failure) {
        runWork(DOCUMENT_WORKER, title, work, success, failure, discarded -> {});
    }

    private <T> void runWork(ExecutorService worker, String title, Work<T> work, Consumer<T> success,
                             Consumer<Exception> failure, Consumer<T> discard) {
        busy = true;
        int token = generation;
        showDialog(new AlertDialog.Builder(this).setTitle(title).setMessage("Aguarde…").setCancelable(false));
        worker.execute(() -> {
            try {
                T result = work.run(token);
                lockHandler.post(() -> {
                    if (generation != token) {
                        discard.accept(result);
                        return;
                    }
                    busy = false;
                    dismissDialog();
                    success.accept(result);
                });
            } catch (Exception e) {
                lockHandler.post(() -> {
                    if (generation != token) return;
                    busy = false;
                    dismissDialog();
                    failure.accept(e);
                });
            }
        });
    }

    private void checkCurrent(int token) {
        if (generation != token) throw new CancellationException();
    }

    private CryptoBox.Session decryptPrivate(char[] password) throws Exception {
        byte[] ciphertext = VaultStorage.readPrivate(storeFile.toPath(), CryptoBox.MAX_ARCHIVE_BYTES);
        try {
            return CryptoBox.open(ciphertext, password);
        } finally {
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private OpenVault readVault(CryptoBox.Session opened) throws Exception {
        byte[] plain = opened.takePlaintext();
        try {
            return new OpenVault(opened, VaultData.parse(plain));
        } catch (Exception e) {
            opened.clear();
            throw e;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private void persist(JSONObject data, CryptoBox.Session key, int token) throws Exception {
        persist(data, key, token, () -> { });
    }

    private void persist(JSONObject data, CryptoBox.Session key, int token, Runnable checkAccess) throws Exception {
        Runnable check = () -> {
            checkCurrent(token);
            checkAccess.run();
        };
        check.run();
        byte[] plain = VaultData.encode(data);
        byte[] encrypted = null;
        try {
            check.run();
            encrypted = CryptoBox.seal(plain, key);
            VaultStorage.writeAtomically(storeFile, encrypted, check);
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    private void acceptVault(OpenVault opened) {
        clearSession();
        session = opened.session;
        vault = opened.data;
        showHome();
    }

    private void saveChanges(JSONObject data, String successMessage, String failureMessage) {
        CryptoBox.Session key = session;
        runWork("Gravando cofre", token -> {
            persist(data, key, token);
            return data;
        }, saved -> {
            vault = saved;
            toast(successMessage);
            showHome();
        }, failure -> error("Falha de gravação", failureMessage));
    }

    private static final class OpenVault {
        final CryptoBox.Session session;
        final JSONObject data;

        OpenVault(CryptoBox.Session session, JSONObject data) {
            this.session = session;
            this.data = data;
        }

        void discard() { session.clear(); }
    }

    private void showLocked() {
        currentPage = "locked";
        boolean exists = storeFile.exists();
        LinearLayout box = begin("PRIVACIDADE • 100% OFFLINE", "ARCA OFFLINE", false);
        space(box,12);
        LinearLayout hero = panel(box);
        text(hero,"▣  Seus dados, sob seu controle.",20,WHITE,true); space(hero,10);
        text(hero,"Documentos, contatos, seguros e informações essenciais em um cofre protegido por criptografia.",14,MUTED,false);
        space(box,14);
        text(box,exists ? "Desbloquear cofre" : "Criar cofre criptografado",20,WHITE,true);
        space(box,12);
        EditText pass = password(box,exists ? "Senha principal" : "Defina uma senha principal forte", !exists);
        EditText confirm = exists ? null : password(box,"Confirme a senha principal", true);
        if (!exists) {
            LinearLayout note = panel(box);
            text(note,"IMPORTANTE",13,RED,true); space(note,6);
            text(note,"Não existe recuperação automática de senha. Se esquecê-la, seus dados podem se tornar inacessíveis. Guarde a senha em local físico seguro e faça backups.",13,MUTED,false);
        }
        button(box,exists ? "Desbloquear" : "Criar meu cofre",true,v -> {
            if (busy) return;
            char[] pw = takePassword(pass);
            if (confirm != null) {
                char[] repeated = takePassword(confirm);
                boolean valid = pw.length >= 10 && Arrays.equals(pw, repeated);
                Arrays.fill(repeated, '\0');
                if (!valid) {
                    Arrays.fill(pw, '\0');
                    error("Senha inválida", "Use pelo menos 10 caracteres e digite a mesma senha nos dois campos.");
                    return;
                }
            }
            runWork(exists ? "Abrindo cofre" : "Criando cofre", token -> {
                try {
                    checkCurrent(token);
                    if (exists) {
                        return readVault(decryptPrivate(pw));
                    }
                    if (storeFile.exists()) throw new IOException("Já existe um cofre neste aparelho.");
                    JSONObject candidate = VaultData.empty();
                    CryptoBox.Session created = CryptoBox.newSession(pw);
                    try {
                        persist(candidate, created, token);
                        return new OpenVault(created, candidate);
                    } catch (Exception e) {
                        created.clear();
                        throw e;
                    }
                } finally {
                    Arrays.fill(pw, '\0');
                }
            }, this::acceptVault, failure -> {
                showLocked();
                if (exists) error("Não foi possível abrir o cofre", "Confira a senha. Caso o arquivo esteja danificado, restaure um backup .arca.");
                else error("Não foi possível criar o cofre", "Verifique o espaço disponível e tente novamente.");
            }, OpenVault::discard);
        });
        button(box,"Restaurar backup criptografado",false,v -> pickFile(REQ_BACKUP_IMPORT,"*/*"));
        space(box,9);
        text(box,"Uso offline • Sem conta • Sem anúncios",12,MUTED,false);
    }

    private void showHome() {
        if (session == null) { showLocked(); return; }
        clearDraft();
        currentPage = "home";
        LinearLayout box = begin("COFRE DIGITAL PRIVADO", "Arca Offline", false);
        LinearLayout intro = panel(box);
        text(intro,"Proteção local ativada",18,WHITE,true); space(intro,6);
        text(intro,"Seus registros estão criptografados no armazenamento privado do celular.",13,MUTED,false);
        space(intro,10);
        int count = records().length();
        text(intro, count == 1 ? "1 registro protegido" : count + " registros protegidos",15,RED,true);
        button(box,"+ Adicionar registro",true,v -> {
            if (records().length() >= VaultData.MAX_RECORDS) {
                error("Limite de registros", "O cofre comporta até 350 registros. Exclua um registro antes de adicionar outro.");
            } else {
                showEditor(-1);
            }
        });
        LinearLayout row = new LinearLayout(this);
        TextView backup = label("⇧  Backup",14,WHITE,true);
        TextView settings = label("⚙  Segurança",14,WHITE,true);
        for (TextView b : new TextView[]{backup,settings}) {
            b.setGravity(Gravity.CENTER); b.setPadding(dp(6),dp(14),dp(6),dp(14)); b.setBackground(bg(PANEL2,12));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1); p.setMargins(dp(0),0,dp(6),dp(0)); row.addView(b,p);
        }
        backup.setOnClickListener(v -> showBackup()); settings.setOnClickListener(v -> showSecurity());
        box.addView(row);
        button(box,"Acesso web",false,v -> showLan());
        space(box,22);
        EditText search = input(box,"Buscar registros","Título, notas, categoria ou telefone",query,false);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { query=s.toString(); updateRecordList(); }
            public void afterTextChanged(Editable e) {}
        });
        Spinner categories = new Spinner(this);
        String[] filters = new String[VaultData.CATEGORIES.length + 1];
        filters[0] = ALL_CATEGORIES;
        System.arraycopy(VaultData.CATEGORIES, 0, filters, 1, VaultData.CATEGORIES.length);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,filters);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categories.setAdapter(adapter); categories.setBackground(bg(PANEL2,10));
        for (int i=0;i<filters.length;i++) if (filter.equals(filters[i])) categories.setSelection(i);
        box.addView(categories,lp(-1,50)); space(box,18);
        recordList = column(); box.addView(recordList,lp(-1,-2));
        categories.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int n,long id){filter=filters[n]; updateRecordList();}
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        updateRecordList();
        space(box,20);
        button(box,"Bloquear cofre",false,v -> lockAndShow());
    }

    private static String normalizeSearch(String text) {
        return DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private String categoryOf(JSONObject record) {
        String category = property(record, "category");
        return category.isEmpty() ? "Outros" : category;
    }

    private void updateRecordList() {
        if(recordList==null || vault==null || !"home".equals(currentPage)) return;
        recordList.removeAllViews();
        JSONArray rows=records(); int shown=0;
        String search = normalizeSearch(query.trim());
        for(int i=rows.length()-1;i>=0;i--) {
            final int idx=i;
            JSONObject r=rows.optJSONObject(i); if(r==null)continue;
            String title=r.optString("title"), category=categoryOf(r);
            if (!ALL_CATEGORIES.equals(filter) && !filter.equals(category)) continue;
            if (!search.isEmpty() && !normalizeSearch(title + " " + category + " " + r.optString("notes") + " " + r.optString("phone")).contains(search)) continue;
            shown++;
            LinearLayout item=panel(recordList);
            text(item,category.toUpperCase(Locale.ROOT),11,RED,true); space(item,5);
            text(item,title,17,WHITE,true);
            String meta=r.optString("date");
            if(!meta.isEmpty()) {space(item,5);text(item,"Data importante: "+meta,12,MUTED,false);}
            if(!r.optString("attachmentData").isEmpty()) {space(item,5);text(item,"▣ Anexo criptografado",12,MUTED,false);}
            item.setOnClickListener(v -> showRecord(idx));
        }
        if(shown==0){
            LinearLayout empty=panel(recordList);
            text(empty,"Nenhum registro encontrado",17,WHITE,true);space(empty,5);
            text(empty,"Adicione seus primeiros documentos ou altere os filtros de busca.",13,MUTED,false);
        }
    }

    private JSONObject getRecord(int index) {
        if(vault==null || index<0 || index>=records().length()) return null;
        return records().optJSONObject(index);
    }
    private String property(JSONObject j,String key) { return j==null ? "" : j.optString(key,""); }
    private void showRecord(int idx) {
        JSONObject r=getRecord(idx); if(r==null){showHome();return;}
        currentPage="record";
        LinearLayout box=begin(categoryOf(r).toUpperCase(Locale.ROOT),property(r,"title"),true);
        LinearLayout details=panel(box);
        for(String[] p:new String[][]{{"Anotações","notes"},{"Telefone","phone"},{"Data importante","date"}}){
            String s=property(r,p[1]); if(!s.isEmpty()) {text(details,p[0],12,MUTED,true);space(details,5);text(details,s,16,WHITE,false);space(details,16);}
        }
        if(property(r,"notes").isEmpty() && property(r,"phone").isEmpty() && property(r,"date").isEmpty())
            text(details,"Nenhuma anotação adicional.",13,MUTED,false);
        String attachment=property(r,"attachmentData");
        if(!attachment.isEmpty()){
            LinearLayout attach=panel(box);
            text(attach,"Anexo: "+safeName(property(r,"attachmentName")),15,WHITE,true);space(attach,8);
            text(attach,"Arquivo protegido dentro do cofre. Ao exportá-lo, a cópia gerada será legível sem senha.",12,MUTED,false);
            space(attach,12);
            String mime = mimeOf(r);
            if (mime.startsWith("image/") || mime.startsWith("text/") || isPdf(r))
                button(attach,"Pré-visualizar anexo",false,v -> previewAttachment(r));
            button(attach,"Exportar anexo descriptografado",false,v -> askExportAttachment(r));
        }
        button(box,"Editar registro",true,v -> showEditor(idx));
        button(box,"Excluir registro",false,v -> showDialog(new AlertDialog.Builder(this)
                .setTitle("Excluir permanentemente?")
                .setMessage("O registro e seu anexo serão removidos do cofre atual. Backups anteriores podem manter cópias.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Excluir",(d,w)->{
                    try {
                        saveChanges(VaultData.replaceRecord(vault, idx, null), "Registro excluído.", "A exclusão não foi gravada.");
                    } catch (Exception e) {
                        error("Falha de gravação", "A exclusão não foi gravada.");
                    }
                })));
    }

    private void showEditor(int index) {
        if(session==null){showLocked();return;}
        JSONObject r=getRecord(index);
        draftAttachmentBase64=property(r,"attachmentData");
        draftAttachmentName=property(r,"attachmentName");
        draftAttachmentMime=property(r,"attachmentMime");
        currentPage="editor";
        LinearLayout box=begin("NOVO REGISTRO SEGURO",index<0?"Adicionar ao cofre":"Editar registro",true);
        formTitle=input(box,"Título *","Ex.: Certidão, contato médico, apólice",property(r,"title"),false);
        text(box,"Categoria",13,MUTED,true);space(box,6);
        formCategory=new Spinner(this);
        String[] cats=VaultData.CATEGORIES;
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,cats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);formCategory.setAdapter(adapter);
        formCategory.setBackground(bg(PANEL2,10));box.addView(formCategory,lp(-1,52));
        if (r != null) {
            for (int i=0;i<cats.length;i++) if(cats[i].equals(categoryOf(r))) formCategory.setSelection(i);
        }
        space(box,17);
        formNotes=input(box,"Notas e informações","Descrição, números de documento, instruções...",property(r,"notes"),true);
        formPhone=input(box,"Telefone (opcional)","Ex.: (21) 99999-9999",property(r,"phone"),false);
        formDate=input(box,"Data importante (opcional)","AAAA-MM-DD",property(r,"date"),false);
        LinearLayout attach=panel(box);
        text(attach,"Arquivo anexo (até 8 MiB)",15,WHITE,true);space(attach,6);
        formAttachmentInfo=label(draftAttachmentBase64.isEmpty()?"Nenhum arquivo adicionado":safeName(draftAttachmentName),13,MUTED,false);
        attach.addView(formAttachmentInfo);space(attach,12);
        button(attach,"Selecionar PDF, foto ou arquivo",false,v -> pickFile(REQ_ATTACH,"*/*"));
        button(attach,"Remover anexo",false,v -> {draftAttachmentBase64="";draftAttachmentName="";draftAttachmentMime="";formAttachmentInfo.setText("Nenhum arquivo adicionado");});
        unsavedChanges = () -> !value(formTitle).equals(property(r, "title"))
                || !value(formNotes).equals(property(r, "notes"))
                || !value(formPhone).equals(property(r, "phone"))
                || !value(formDate).equals(property(r, "date"))
                || !draftAttachmentBase64.equals(property(r, "attachmentData"))
                || !draftAttachmentName.equals(property(r, "attachmentName"))
                || !draftAttachmentMime.equals(property(r, "attachmentMime"))
                || !formCategory.getSelectedItem().toString().equals(r == null ? cats[0] : categoryOf(r));
        button(box,"Salvar registro criptografado",true,v -> {
            if (busy) return;
            try {
                String title=value(formTitle), notes=value(formNotes), phone=value(formPhone), date=value(formDate);
                if(title.isEmpty()||title.length()>120) {error("Título inválido","Digite um título de até 120 caracteres.");return;}
                if(notes.length()>5000||phone.length()>120) {error("Campo excede limite","Use até 5.000 caracteres nas notas e até 120 no telefone.");return;}
                if(!date.isEmpty()) {
                    try{LocalDate.parse(date);}catch(Exception e){error("Data inválida","Use o formato AAAA-MM-DD, por exemplo 2027-06-30.");return;}
                }
                JSONObject record=r==null ? new JSONObject() : VaultData.copyObject(r);
                if(r==null)record.put("id",UUID.randomUUID().toString());
                record.put("title",title);record.put("category",formCategory.getSelectedItem().toString());
                record.put("notes",notes);record.put("phone",phone);record.put("date",date);
                record.put("attachmentData",draftAttachmentBase64==null?"":draftAttachmentBase64);
                record.put("attachmentName",draftAttachmentName==null?"":draftAttachmentName);
                record.put("attachmentMime",draftAttachmentMime==null?"":draftAttachmentMime);
                saveChanges(VaultData.replaceRecord(vault, index, record), "Registro salvo com criptografia.",
                        "Verifique o espaço disponível e o limite total de 32 MiB do cofre.");
            }catch(Exception e){error("Erro ao salvar","Verifique o espaço do aparelho e o tamanho dos anexos. Não foi possível confirmar a gravação.");}
        });
    }

    private void showBackup() {
        currentPage="backup";
        LinearLayout box=begin("PROTEÇÃO E RECUPERAÇÃO","Backups seguros",true);
        LinearLayout explain=panel(box);
        text(explain,"Backup portátil criptografado",18,WHITE,true);space(explain,8);
        text(explain,"O backup .arca contém registros e anexos protegidos pela mesma senha principal. Pode ser guardado em pendrive, cartão SD ou computador.",14,MUTED,false);
        space(explain,9);
        text(explain,"Guarde o arquivo em pelo menos dois locais e teste a restauração antes de depender dele.",13,RED,true);
        button(box,"Exportar arquivo .arca criptografado",true,v -> exportBackupPicker());
        button(box,"Restaurar um backup .arca",false,v -> pickFile(REQ_BACKUP_IMPORT,"*/*"));
    }
    private void showSecurity() {
        currentPage="security";
        LinearLayout box=begin("CONFIGURAÇÕES DO COFRE","Segurança",true);
        LinearLayout info=panel(box);
        text(info,"AES-256-GCM",18,WHITE,true);space(info,7);
        String count = NumberFormat.getIntegerInstance(Locale.forLanguageTag("pt-BR")).format(session.iterations());
        text(info,"A chave do cofre é derivada da senha com PBKDF2-HMAC-SHA256 (" + count + " iterações). Cada gravação usa um nonce aleatório novo.",13,MUTED,false);
        space(info,10);
        text(info,"Cofre no celular • Acesso web opcional na rede local • Capturas de tela bloqueadas",12,RED,true);
        button(box,"Alterar senha principal",true,v -> changePassword());
        button(box,"Exportar backup",false,v -> showBackup());
        button(box,"Bloquear agora",false,v -> lockAndShow());
        LinearLayout warning=panel(box);
        text(warning,"Cuidados essenciais",16,WHITE,true);space(warning,9);
        text(warning,"• Perder a senha significa perder o acesso ao cofre.\n• A exclusão do aplicativo apaga o cofre local.\n• A exportação de anexos produz cópias NÃO criptografadas.\n• O conteúdo fica visível enquanto o aplicativo estiver desbloqueado.\n• Evite celulares comprometidos ou com root não confiável.",13,MUTED,false);
    }
    private void changePassword() {
        if(session==null || busy)return;
        LinearLayout container=column();container.setPadding(dp(22),dp(8),dp(22),dp(6));
        EditText first=password(container,"Nova senha", true);
        EditText second=password(container,"Repetir nova senha", true);
        AlertDialog dialog = showDialog(new AlertDialog.Builder(this).setTitle("Trocar senha principal").setView(container)
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Alterar",(d,w)->{
                    char[] a = takePassword(first);
                    char[] b = takePassword(second);
                    boolean valid = a.length >= 10 && Arrays.equals(a, b);
                    Arrays.fill(b, '\0');
                    if (!valid) {
                        Arrays.fill(a, '\0');
                        error("Senha inválida", "Use 10 ou mais caracteres e confirme a senha corretamente.");
                        return;
                    }
                    JSONObject data = vault;
                    runWork("Alterando senha", token -> {
                        CryptoBox.Session next = null;
                        try {
                            checkCurrent(token);
                            next = CryptoBox.newSession(a);
                            persist(data, next, token);
                            return next;
                        } catch (Exception e) {
                            if (next != null) next.clear();
                            throw e;
                        } finally {
                            Arrays.fill(a, '\0');
                        }
                    }, next -> {
                        CryptoBox.Session previous = session;
                        session = next;
                        previous.clear();
                        toast("Senha alterada. Backups antigos continuam com a senha antiga.");
                        showSecurity();
                    }, failure -> error("Falha na alteração", "A senha anterior continua válida. Verifique o espaço disponível e tente novamente."),
                            CryptoBox.Session::clear);
                }));
        dialog.setOnDismissListener(ignored -> {
            first.getText().clear();
            second.getText().clear();
        });
    }

    private void previewAttachment(JSONObject r) {
        String name = safeName(property(r, "attachmentName"));
        Consumer<Exception> failed = failure -> error("Falha na prévia", "Não foi possível abrir o arquivo.");
        if (isPdf(r)) {
            runWork("Preparando PDF", token -> decodeAttachment(r), bytes -> trackDialog(PdfPreview.show(this, name, bytes)), failed,
                    bytes -> Arrays.fill(bytes, (byte) 0));
        } else if (mimeOf(r).startsWith("image/")) {
            runWork("Preparando imagem", token -> decodeImage(r), picture -> {
                ImageView view = new ImageView(this);
                view.setImageBitmap(picture);
                view.setContentDescription(name);
                view.setAdjustViewBounds(true);
                view.setPadding(dp(8), dp(8), dp(8), dp(8));
                showDialog(new AlertDialog.Builder(this).setTitle(name).setView(view).setPositiveButton("Fechar", null));
            }, failed, Bitmap::recycle);
        } else {
            runWork("Preparando texto", token -> {
                byte[] bytes = decodeAttachment(r);
                try {
                    int limit = Math.min(bytes.length, 64 * 1024);
                    String preview = new String(bytes, 0, limit, StandardCharsets.UTF_8);
                    return limit == bytes.length ? preview : preview + "\n\n[Apenas o início do arquivo é exibido.]";
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }, preview -> showDialog(new AlertDialog.Builder(this).setTitle(name).setMessage(preview).setPositiveButton("Fechar", null)), failed);
        }
    }

    private Bitmap decodeImage(JSONObject record) throws IOException {
        byte[] bytes = decodeAttachment(record);
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || (long) options.outWidth * options.outHeight > 100_000_000) {
                throw new IOException("Imagem inválida ou com resolução muito alta.");
            }
            int largest = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            while (largest / options.inSampleSize > 1600) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            Bitmap picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (picture == null) throw new IOException("Imagem não suportada.");
            return picture;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private byte[] decodeAttachment(JSONObject record) { return Base64.decode(property(record, "attachmentData"), Base64.NO_WRAP); }

    private boolean isPdf(JSONObject record) {
        String mime = mimeOf(record);
        return mime.equals("application/pdf") || (mime.equals("application/octet-stream")
                && property(record, "attachmentName").toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    private String mimeOf(JSONObject record) { return safeMime(property(record, "attachmentMime")); }

    static String safeMime(String mime) {
        if (mime == null || mime.length() > 255 || !MIME_TYPE.matcher(mime).matches()) return "application/octet-stream";
        return mime.toLowerCase(Locale.ROOT);
    }

    static String safeName(String raw) {
        if (raw == null) return "arquivo";
        String clean = FILE_NAME_DISALLOWED.matcher(raw).replaceAll("_").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "arquivo";
        return clean.length() > 140 ? clean.substring(0, 140) : clean;
    }
    private void askExportAttachment(JSONObject r) {
        showDialog(new AlertDialog.Builder(this)
            .setTitle("Exportação sem criptografia")
            .setMessage("O anexo exportado NÃO será protegido pela senha do Arca Offline. Evite pastas sincronizadas com nuvem e compartilhe apenas quando necessário.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Escolher destino",(d,w)->{
                pendingAttachment = r;
                pickSave(REQ_ATTACHMENT_EXPORT, mimeOf(r), safeName(property(r, "attachmentName")));
            }));
    }
    private void exportBackupPicker() {
        if(session==null)return;
        pickSave(REQ_BACKUP_EXPORT,"application/octet-stream","arca-backup-"+LocalDate.now()+".arca");
    }
    private void pickSave(int request,String mime,String fileName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(safeMime(mime));
        intent.putExtra(Intent.EXTRA_TITLE, safeName(fileName));
        launchPicker(request, intent);
    }
    private void pickFile(int request,String mime) {
        launchPicker(request, new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime));
    }

    private void launchPicker(int action, Intent intent) {
        if (pendingPicker != 0 || busy) return;
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        pendingPicker = action;
        pickerRequestCode = nextRequest++;
        if (nextRequest > 60_000) nextRequest = 100;
        pickerDeadline = SystemClock.elapsedRealtime() + PICKER_TIMEOUT_MS;
        try {
            startActivityForResult(intent, pickerRequestCode);
            lockHandler.postDelayed(delayedLock, PICKER_TIMEOUT_MS);
        } catch (Exception e) {
            pendingPicker = 0;
            pickerRequestCode = 0;
            pendingAttachment = null;
            error("Seletor indisponível", "Não foi possível abrir a seleção de arquivos.");
        }
    }
    private String nameOf(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return safeName(cursor.getString(0));
        } catch (RuntimeException ignored) {}
        return "arquivo";
    }
    @Override protected void onActivityResult(int request,int result,Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == expiredRequest) {
            expiredRequest = 0;
            if (result == RESULT_OK) toast("O tempo de seleção terminou. Inicie a operação novamente.");
            return;
        }
        if (request != pickerRequestCode || pendingPicker == 0) return;
        if (SystemClock.elapsedRealtime() >= pickerDeadline) {
            expirePicker();
            return;
        }
        int action = pendingPicker;
        JSONObject attachment = pendingAttachment;
        pendingAttachment = null;
        pendingPicker = 0;
        pickerRequestCode = 0;
        lockHandler.removeCallbacks(delayedLock);
        if (result != RESULT_OK || intent == null) return;
        Uri uri = intent.getData();
        if (uri == null || !"content".equals(uri.getScheme())) {
            error("Arquivo indisponível", "Escolha um arquivo pelo seletor do Android.");
            return;
        }
        switch (action) {
            case REQ_ATTACH:
                if (session != null && "editor".equals(currentPage)) loadAttachment(uri);
                break;
            case REQ_BACKUP_EXPORT:
                if (session != null) exportBackup(uri);
                break;
            case REQ_BACKUP_IMPORT:
                askRestorePassword(uri);
                break;
            case REQ_LAN_CERTIFICATE:
                if (lanIdentity != null && "lan".equals(currentPage) && lanServer == null) exportLanCertificate(uri);
                break;
            case REQ_ATTACHMENT_EXPORT:
                if (session != null && attachment != null) exportAttachment(uri, attachment);
                break;
        }
    }

    private void loadAttachment(Uri uri) {
        runDocumentWork("Lendo anexo", token -> {
            checkCurrent(token);
            byte[] raw = VaultStorage.read(getContentResolver().openInputStream(uri), VaultData.MAX_ATTACHMENT);
            try {
                if (raw.length == 0) throw new IOException("Arquivo vazio.");
                String mime = safeMime(getContentResolver().getType(uri));
                return new Attachment(Base64.encodeToString(raw, Base64.NO_WRAP), nameOf(uri), mime, raw.length);
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        }, imported -> {
            draftAttachmentBase64 = imported.base64;
            draftAttachmentName = imported.name;
            draftAttachmentMime = imported.mime;
            int kib = (imported.length + 1023) / 1024;
            formAttachmentInfo.setText(imported.name + " (" + kib + " KiB)");
            toast("Anexo pronto. Toque em salvar para criptografar.");
        }, failure -> error("Não foi possível anexar", "Escolha um arquivo acessível, com conteúdo e de até 8 MiB."));
    }

    private static final class Attachment {
        final String base64, name, mime;
        final int length;

        Attachment(String base64, String name, String mime, int length) {
            this.base64 = base64;
            this.name = name;
            this.mime = mime;
            this.length = length;
        }
    }

    private OutputStream openDestination(Uri uri) throws IOException {
        OutputStream output = getContentResolver().openOutputStream(uri, "wt");
        if (output == null) throw new IOException("Destino indisponível.");
        return output;
    }

    private void exportBackup(Uri uri) {
        runDocumentWork("Exportando backup", token -> {
            checkCurrent(token);
            try (var source = Files.newInputStream(storeFile.toPath()); OutputStream output = openDestination(uri)) {
                VaultStorage.copy(source, output, CryptoBox.MAX_ARCHIVE_BYTES);
            }
            return null;
        }, done -> toast("Backup criptografado exportado. Guarde a senha em local seguro."),
                failure -> error("Falha na exportação", "Verifique o destino e o espaço disponível. O arquivo externo pode estar incompleto."));
    }

    private void exportAttachment(Uri uri, JSONObject record) {
        runDocumentWork("Exportando anexo", token -> {
            checkCurrent(token);
            try (OutputStream output = openDestination(uri)) {
                checkCurrent(token);
                byte[] bytes = decodeAttachment(record);
                try {
                    checkCurrent(token);
                    output.write(bytes);
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
            return null;
        }, done -> toast("Anexo exportado sem criptografia."), failure -> error("Falha na exportação",
                "Verifique o destino e o espaço disponível. O arquivo externo pode conter parte do anexo sem criptografia."));
    }

    private void askRestorePassword(Uri uri) {
        LinearLayout contents=column();contents.setPadding(dp(20),dp(8),dp(20),0);
        EditText pw=password(contents,"Senha do backup (pode ser antiga)", false);
        AlertDialog dialog = showDialog(new AlertDialog.Builder(this).setTitle("Abrir e restaurar backup")
            .setMessage("O aplicativo verifica a senha e o conteúdo antes de restaurar o cofre.")
            .setView(contents).setNegativeButton("Cancelar",null)
            .setPositiveButton("Validar e restaurar",(d,w)->{
                char[] pass = takePassword(pw);
                runWork(DOCUMENT_WORKER, "Validando backup", token -> {
                    byte[] ciphertext = null;
                    try {
                        checkCurrent(token);
                        ciphertext = VaultStorage.read(getContentResolver().openInputStream(uri), CryptoBox.MAX_ARCHIVE_BYTES);
                        checkCurrent(token);
                        return new ReadyRestore(readVault(CryptoBox.open(ciphertext, pass)), ciphertext);
                    } catch (Exception e) {
                        if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
                        throw e;
                    } finally {
                        Arrays.fill(pass, '\0');
                    }
                }, this::confirmRestore, failure -> error("Restauração negada",
                        "Confira a senha e o formato do arquivo .arca. O cofre atual foi preservado."), ReadyRestore::discard);
            }));
        dialog.setOnDismissListener(ignored -> pw.getText().clear());
    }

    private static final class ReadyRestore {
        final OpenVault opened;
        final byte[] ciphertext;

        ReadyRestore(OpenVault opened, byte[] ciphertext) {
            this.opened = opened;
            this.ciphertext = ciphertext;
        }

        void discard() {
            Arrays.fill(ciphertext, (byte) 0);
            opened.discard();
        }
    }

    private void confirmRestore(ReadyRestore ready) {
        if (!storeFile.exists()) {
            commitRestore(ready, false);
            return;
        }
        ReadyRestore[] confirmed = {ready};
        AlertDialog dialog = showDialog(new AlertDialog.Builder(this).setTitle("Substituir o cofre atual?")
                .setMessage("O backup verificado substituirá todos os registros atuais. A senha do backup passará a abrir o cofre. Para guardar os dados atuais, cancele e exporte um backup antes de continuar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Substituir cofre", (d,w) -> {
                    confirmed[0] = null;
                    commitRestore(ready, true);
                }));
        dialog.setOnDismissListener(ignored -> {
            if (confirmed[0] != null) confirmed[0].discard();
        });
    }

    private void commitRestore(ReadyRestore ready, boolean replace) {
        runWork("Gravando backup restaurado", token -> {
            try {
                checkCurrent(token);
                if (!replace && storeFile.exists()) throw new IOException("O cofre foi criado durante a operação.");
                VaultStorage.writeAtomically(storeFile, ready.ciphertext, () -> checkCurrent(token));
                return ready.opened;
            } catch (Exception e) {
                ready.opened.discard();
                throw e;
            } finally {
                Arrays.fill(ready.ciphertext, (byte) 0);
            }
        }, opened -> {
            acceptVault(opened);
            toast("Backup restaurado e validado.");
        }, failure -> error("Falha na restauração", "Não foi possível gravar o backup. O cofre atual foi preservado."), OpenVault::discard);
    }

    private void showLan() {
        currentPage = "lan";
        recordList = null;
        LinearLayout box = begin("REDE LOCAL", "Acesso web", true);
        if (lanServer != null) {
            showLanActive(box);
            return;
        }
        text(box, "Gerencie o cofre pelo navegador de outro dispositivo na mesma rede Wi-Fi ou Ethernet.", 15, WHITE, false);
        space(box, 16);
        if (lanIdentity != null && isLanAvailable(lanLocation)) {
            text(box, "Endereço do celular • " + lanLocation.address.getHostAddress(), 15, WHITE, true);
            space(box, 9);
            text(box, "Salve o certificado público, confira sua impressão digital e instale-o no navegador ou no dispositivo que vai acessar o cofre.", 14, MUTED, false);
            space(box, 9);
            TextView fingerprint = label("SHA-256\n" + lanIdentity.certificateSha256(), 12, WHITE, false);
            fingerprint.setTextIsSelectable(true);
            box.addView(fingerprint);
            space(box, 8);
            button(box, "Salvar certificado público", false,
                    view -> pickSave(REQ_LAN_CERTIFICATE, "application/x-x509-ca-cert", "Arca-Offline-LAN.crt"));
            button(box, "Ativar acesso HTTPS", true, view -> startLan());
            button(box, "Escolher outra rede", false, view -> {
                lanLocation = null;
                lanIdentity = null;
                showLan();
            });
            return;
        }

        lanLocation = null;
        lanIdentity = null;
        List<LanLocation> available = localAddresses();
        if (available.isEmpty()) {
            text(box, "Conecte o celular a uma rede Wi-Fi ou Ethernet com endereço IPv4 privado.", 14, MUTED, false);
            button(box, "Procurar redes novamente", false, view -> showLan());
            return;
        }
        ArrayAdapter<LanLocation> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, available);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner networks = new Spinner(this);
        networks.setAdapter(adapter);
        networks.setContentDescription("Endereço de rede local");
        networks.setBackground(bg(PANEL2, 10));
        box.addView(networks, lp(-1, 50));
        space(box, 12);
        button(box, "Preparar acesso HTTPS", true, view -> {
            LanLocation selected = (LanLocation) networks.getSelectedItem();
            runWork("Preparando certificado local", token -> {
                checkCurrent(token);
                return LanTls.create(this, selected.address);
            }, identity -> {
                lanLocation = selected;
                lanIdentity = identity;
                showLan();
            }, failure -> error("Falha no certificado", "Confira o relógio do celular e tente preparar o acesso novamente."));
        });
    }

    private void showLanActive(LinearLayout box) {
        LinearLayout connection = panel(box);
        text(connection, "Abra este endereço no navegador", 16, WHITE, true);
        space(connection, 8);
        TextView url = label(lanServer.origin(), 18, WHITE, true);
        url.setTextIsSelectable(true);
        connection.addView(url);
        space(connection, 15);
        lanStatus = label("", 18, RED, true);
        lanStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connection.addView(lanStatus);
        space(connection, 8);
        text(connection, "Use o código uma vez. A senha principal fica no celular.", 14, MUTED, false);
        text(box, "Mantenha esta tela aberta. O acesso fecha ao sair, bloquear o cofre, trocar de rede ou atingir o prazo da sessão.", 14, MUTED, false);
        space(box, 9);
        button(box, "Encerrar acesso web", true, view -> endLan(false, "Acesso web encerrado."));
        updateLanStatus();
    }

    private void startLan() {
        if (busy || !isLanAvailable(lanLocation)) {
            showLan();
            return;
        }
        LanLocation location = lanLocation;
        LanTls identity = lanIdentity;
        CryptoBox.Session key = session;
        int token = generation;
        LanVault remote = new LanVault(STORAGE_WORKER, vault, storeFile.toPath(),
                (data, allowed) -> persist(data, key, token, allowed));
        lanStarting = true;
        runWork("Ativando acesso local", workToken -> {
            checkCurrent(workToken);
            return LanServer.start(getApplicationContext(), location.address, identity.context(), remote,
                    () -> lockHandler.post(() -> {
                        if (lanVault == remote) endLan(false, "A sessão do navegador terminou.");
                    }));
        }, server -> {
            lanStarting = false;
            lanVault = remote;
            lanServer = server;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showLan();
        }, failure -> {
            lanStarting = false;
            remote.revoke();
            error("Acesso web indisponível", "Confira se o celular ainda está conectado à rede selecionada.");
        }, abandoned -> {
            remote.revoke();
            abandoned.close();
        });
    }

    private void updateLanStatus() {
        lockHandler.removeCallbacks(monitorLan);
        LanServer server = lanServer;
        if (server == null) return;
        if (!server.isRunning() || !isLanAvailable(lanLocation)) {
            endLan(false, "A conexão local terminou.");
            return;
        }
        String code = server.pairingCode();
        String status = code == null ? "Navegador pareado" : "Código de pareamento\n" + code;
        if (lanStatus != null && !status.contentEquals(lanStatus.getText())) lanStatus.setText(status);
        lockHandler.postDelayed(monitorLan, 1_000);
    }

    private LanVault disconnectLan() {
        lockHandler.removeCallbacks(monitorLan);
        LanVault remote = lanVault;
        LanServer server = lanServer;
        lanVault = null;
        lanServer = null;
        lanStarting = false;
        lanStatus = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (remote != null) remote.revoke();
        if (server != null) server.close();
        return remote;
    }

    private void endLan(boolean back, String message) {
        LanVault stopped = disconnectLan();
        if (stopped == null) {
            showHome();
            return;
        }
        // Let an already-authorized atomic write finish before exposing native editing again.
        runWork("Encerrando acesso web", token -> {
            checkCurrent(token);
            return stopped.settledData();
        }, settled -> {
            vault = settled;
            if (back) showHome();
            else showLan();
            if (message != null) toast(message);
        }, failure -> lockAndShow());
    }

    private void exportLanCertificate(Uri uri) {
        byte[] certificate = lanIdentity.certificateDer();
        runDocumentWork("Salvando certificado público", token -> {
            checkCurrent(token);
            try (OutputStream output = openDestination(uri)) {
                output.write(certificate);
            }
            return null;
        }, unused -> toast("Certificado público salvo."),
                failure -> error("Falha ao salvar", "Escolha um destino local disponível."));
    }

    private boolean isLanAvailable(LanLocation location) {
        if (location == null) return false;
        for (LanLocation available : localAddresses()) {
            if (location.network.equals(available.network) && location.address.equals(available.address)) return true;
        }
        return false;
    }

    private List<LanLocation> localAddresses() {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        List<LanLocation> locations = new ArrayList<>();
        if (connectivity == null) return locations;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            boolean wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (!wifi && !ethernet) continue;
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties == null) continue;
            for (LinkAddress link : properties.getLinkAddresses()) {
                if (link.getAddress() instanceof Inet4Address) {
                    Inet4Address address = (Inet4Address) link.getAddress();
                    if (LanTls.isPrivateAddress(address)) locations.add(new LanLocation(network, address, wifi));
                }
            }
        }
        return locations;
    }

    private static final class LanLocation {
        final Network network;
        final Inet4Address address;
        final boolean wifi;

        LanLocation(Network network, Inet4Address address, boolean wifi) {
            this.network = network;
            this.address = address;
            this.wifi = wifi;
        }

        @Override public String toString() {
            return (wifi ? "Wi-Fi • " : "Ethernet • ") + address.getHostAddress();
        }
    }

    private void leavePage() {
        if (session == null || busy) return;
        if ("lan".equals(currentPage)) {
            endLan(true, null);
            return;
        }
        if (unsavedChanges != null && unsavedChanges.getAsBoolean()) {
            showDialog(new AlertDialog.Builder(this).setTitle("Descartar alterações?")
                    .setMessage("As alterações do registro ainda não foram gravadas no cofre.")
                    .setNegativeButton("Continuar editando", null)
                    .setPositiveButton("Descartar", (d,w) -> showHome()));
        } else {
            showHome();
        }
    }

    @Override public void onBackPressed() {
        if (busy) return;
        if(session!=null && !"home".equals(currentPage))leavePage();
        else if(session!=null)lockAndShow();
        else super.onBackPressed();
    }
}
