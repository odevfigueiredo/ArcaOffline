"use strict";

const $ = id => document.getElementById(id);
const dialog = $("record-dialog");
const rasterTypes = new Set(["image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/avif"]);
const sectionButtons = [...document.querySelectorAll("[data-view]")];

let snapshot = null;
let connected = false;
let refreshing = false;
let healthCheck = false;
let generation = 0;
let section = "records";
let category = "";
let attachmentsOnly = false;
let current = null;
let noticeTimer;
let previewUrl;
let listAction;
const timers = new Map();
const objectUrls = new Set();

class RequestError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

async function request(path, { body, timeout = 10000, binary = false } = {}) {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), timeout);
    timers.set(controller, timer);
    try {
        const response = await fetch(path, {
            method: body === undefined ? "GET" : "POST",
            body: body === undefined ? undefined : JSON.stringify(body),
            headers: body === undefined ? undefined : { "Content-Type": "application/json" },
            credentials: "same-origin",
            mode: "same-origin",
            cache: "no-store",
            redirect: "error",
            referrerPolicy: "no-referrer",
            signal: controller.signal
        });
        const json = (response.headers.get("Content-Type") || "").startsWith("application/json");
        if (!response.ok) {
            const result = json ? await response.json().catch(() => null) : null;
            throw new RequestError(response.status, result?.error || "O celular recusou a operação.");
        }
        if (binary) return await response.blob();
        if (!json) throw new RequestError(502, "O celular enviou uma resposta inesperada.");
        return await response.json();
    } catch (error) {
        if (error instanceof RequestError) throw error;
        throw new RequestError(0, "A conexão foi interrompida. Confira se o painel continua ativo no celular.");
    } finally {
        window.clearTimeout(timer);
        timers.delete(controller);
    }
}

function message(id, text = "", kind = "") {
    const element = $(id);
    element.textContent = text;
    if (kind) element.dataset.kind = kind;
    else delete element.dataset.kind;
}

function notice(text, error = false) {
    window.clearTimeout(noticeTimer);
    message("dashboard-notice", text, error ? "error" : "");
    $("dashboard-notice").hidden = !text;
    if (text && !error) {
        noticeTimer = window.setTimeout(() => notice(""), 5000);
    }
}

function element(name, className, text) {
    const node = document.createElement(name);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function icon(name) {
    const node = element("i", "icon icon-" + name);
    node.setAttribute("aria-hidden", "true");
    return node;
}

function retainBlob(blob) {
    const url = URL.createObjectURL(blob);
    objectUrls.add(url);
    return url;
}

function releaseBlob(url) {
    URL.revokeObjectURL(url);
    objectUrls.delete(url);
}

function clearPreview() {
    $("attachment-preview").replaceChildren();
    $("attachment-preview").hidden = true;
    $("view-attachment").textContent = "Ver anexo";
    if (previewUrl) releaseBlob(previewUrl);
    previewUrl = undefined;
}

function clearDialog() {
    clearPreview();
    current = null;
    $("detail-fields").replaceChildren();
    $("attachment-name").textContent = "";
    $("dialog-title").textContent = "";
    $("dialog-description").textContent = "";
    $("record-form").reset();
    for (const id of ["record-feedback", "delete-feedback", "attachment-feedback"]) message(id);
    for (const id of ["save-record", "confirm-delete", "view-attachment", "download-attachment"]) $(id).disabled = false;
}

function clearSessionView() {
    generation++;
    connected = false;
    snapshot = null;
    refreshing = false;
    healthCheck = false;
    for (const [controller, timer] of timers) {
        window.clearTimeout(timer);
        controller.abort();
    }
    timers.clear();
    if (dialog.open) dialog.close();
    clearDialog();
    for (const url of objectUrls) releaseBlob(url);
    category = "";
    attachmentsOnly = false;
    $("record-search").value = "";
    $("pair-code").value = "";
    $("record-rows").replaceChildren();
    $("category-nav").replaceChildren();
    for (const id of ["record-count", "count-all", "count-attached", "count-categories"]) $(id).textContent = "";
    showListStatus("Carregando registros…", "");
    $("dashboard").hidden = true;
    $("pair-view").hidden = false;
    $("skip-link").href = "#pair-code";
    $("pair-submit").disabled = false;
    $("logout").disabled = false;
    $("backup-download").disabled = false;
    message("backup-feedback");
    notice("");
}

function unpair(text, error = true) {
    clearSessionView();
    message("pair-feedback", text, error ? "error" : "");
    $("pair-code").focus({ preventScroll: true });
}

function report(error, fallback) {
    if (error.status === 401) {
        unpair("A sessão terminou. Confira o código no celular para acessar novamente.");
    } else {
        fallback(error.message);
    }
}

function sanitizeSnapshot(data) {
    if (!Number.isSafeInteger(data?.revision) || !Array.isArray(data.records) || !Array.isArray(data.categories)) {
        throw new RequestError(502, "O celular enviou um formato de registros incompatível.");
    }
    return data;
}

function applySnapshot(data) {
    const next = sanitizeSnapshot(data);
    if (snapshot && next.revision < snapshot.revision) return;
    const previousCategories = snapshot?.categories;
    snapshot = next;
    if (!snapshot.categories.includes(category)) category = "";
    if (!previousCategories || previousCategories.join() !== snapshot.categories.join()) renderSelects();
    renderRecords();
}

function renderSelects() {
    const filters = $("category-filter");
    const select = $("record-category");
    filters.replaceChildren(new Option("Todas", ""));
    select.replaceChildren();
    for (const name of snapshot.categories) {
        filters.add(new Option(name, name));
        select.add(new Option(name, name));
    }
}

function showDashboard(data) {
    sanitizeSnapshot(data);
    connected = true;
    $("pair-code").value = "";
    $("pair-view").hidden = true;
    $("dashboard").hidden = false;
    $("skip-link").href = "#workspace";
    $("workspace").focus({ preventScroll: true });
    navigate("records");
    applySnapshot(data);
}

function navigate(next) {
    section = next;
    $("records-view").hidden = section !== "records";
    $("backup-view").hidden = section !== "backup";
    $("section-name").textContent = section === "records" ? "Registros" : "Backup";
    for (const button of sectionButtons) {
        if (button.dataset.view === section) button.setAttribute("aria-current", "page");
        else button.removeAttribute("aria-current");
    }
    if (section === "backup") message("backup-feedback");
}

function showListStatus(title, description, actionText, action) {
    $("records-scroll").hidden = true;
    $("list-status").hidden = false;
    $("list-title").textContent = title;
    $("list-description").textContent = description;
    $("list-description").hidden = !description;
    $("list-action").hidden = !action;
    $("list-action").textContent = actionText || "";
    listAction = action;
}

function searchKey(value) {
    return String(value || "").normalize("NFD").replace(/\p{M}/gu, "").toLocaleLowerCase("pt-BR");
}

function recordMatches(record, words) {
    if ((category && record.category !== category) || (attachmentsOnly && !record.attachment)) return false;
    const haystack = searchKey([record.title, record.notes, record.category, record.phone, record.attachment?.name].join(" "));
    return words.every(word => haystack.includes(word));
}

function renderCategoryNav(counts) {
    const nav = $("category-nav");
    const categories = ["", ...snapshot.categories];
    if (nav.childElementCount !== categories.length || categories.some((name, index) => nav.children[index].dataset.filter !== name)) {
        const fragment = document.createDocumentFragment();
        for (const name of categories) {
            const button = element("button", "category-button");
            button.type = "button";
            button.dataset.category = name;
            button.dataset.filter = name;
            const dot = element("span", "category-dot");
            dot.setAttribute("aria-hidden", "true");
            button.append(dot, element("span", "", name || "Todas"), element("span", "category-count"));
            button.addEventListener("click", () => selectCategory(name));
            fragment.append(button);
        }
        nav.replaceChildren(fragment);
    }
    for (const button of nav.children) {
        const name = button.dataset.filter;
        button.setAttribute("aria-pressed", String(name === category));
        button.lastChild.textContent = name ? counts.get(name) || 0 : snapshot.records.length;
    }
    $("category-filter").value = category;
}

function formatDate(value) {
    if (!value) return "—";
    const parts = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    return parts ? parts[3] + "/" + parts[2] + "/" + parts[1] : value;
}

function recordRow(record) {
    const row = element("tr");
    const titleCell = element("td");
    const title = element("button", "row-title");
    const file = element("span", "row-file");
    file.append(icon("file"));
    title.type = "button";
    title.append(file, element("span", "", record.title));
    title.addEventListener("click", () => openRecord(record.id));
    titleCell.append(title);

    const categoryCell = element("td");
    const tag = element("span", "tag", record.category);
    tag.dataset.category = record.category;
    categoryCell.append(tag);
    const date = element("td", "row-date");
    if (record.date) {
        const time = element("time", "", formatDate(record.date));
        time.dateTime = record.date;
        date.append(time);
    } else {
        date.textContent = "—";
    }
    const attachment = element("td");
    if (record.attachment) {
        const caption = element("span", "row-attachment");
        caption.append(icon("clip"), "1 arquivo");
        attachment.append(caption);
    } else {
        attachment.textContent = "—";
    }
    const actionCell = element("td");
    const action = element("button", "button button-quiet row-action", "›");
    action.type = "button";
    action.setAttribute("aria-label", "Abrir " + record.title);
    action.addEventListener("click", () => openRecord(record.id));
    actionCell.append(action);
    row.append(titleCell, categoryCell, date, attachment, actionCell);
    return row;
}

function renderRecords() {
    if (!snapshot) return;
    const records = snapshot.records;
    const words = searchKey($("record-search").value).trim().split(/\s+/u).filter(Boolean);
    const counts = new Map();
    let attached = 0;
    const rows = document.createDocumentFragment();
    let visible = 0;
    for (const record of records) {
        counts.set(record.category, (counts.get(record.category) || 0) + 1);
        if (record.attachment) attached++;
        if (recordMatches(record, words)) {
            rows.append(recordRow(record));
            visible++;
        }
    }
    $("count-all").textContent = records.length;
    $("count-attached").textContent = attached;
    $("count-categories").textContent = counts.size;
    $("all-records").setAttribute("aria-pressed", String(!attachmentsOnly && !category));
    $("attached-records").setAttribute("aria-pressed", String(attachmentsOnly));
    $("record-count").textContent = visible;
    $("record-rows").replaceChildren(rows);
    renderCategoryNav(counts);
    if (visible) {
        $("records-scroll").hidden = false;
        $("list-status").hidden = true;
    } else if (records.length === 0) {
        showListStatus("Seu cofre está vazio", "Adicione o primeiro documento, contato ou informação importante.",
            "Criar primeiro registro", () => openEditor(null));
    } else {
        showListStatus("Nenhum registro encontrado", "Altere a busca ou remova os filtros para ver outros registros.",
            "Limpar filtros", resetFilters);
    }
}

function selectCategory(next) {
    category = next;
    attachmentsOnly = false;
    navigate("records");
    renderRecords();
}

function resetFilters() {
    $("record-search").value = "";
    selectCategory("");
}

async function refreshRecords(notify = false) {
    if (refreshing || !connected) return;
    const epoch = generation;
    refreshing = true;
    $("refresh-records").disabled = true;
    if (!snapshot) showListStatus("Carregando registros…", "");
    try {
        const fresh = await request("/api/state");
        if (epoch !== generation) return;
        applySnapshot(fresh);
        if (notify) notice("Lista atualizada.");
    } catch (error) {
        if (epoch !== generation) return;
        report(error, text => {
            if (snapshot) notice(text, true);
            else showListStatus("Não foi possível carregar o cofre", text, "Tentar novamente", () => refreshRecords());
        });
    } finally {
        if (epoch === generation) {
            refreshing = false;
            $("refresh-records").disabled = false;
        }
    }
}

function showDialogMode(mode, title, description = "") {
    $("dialog-title").textContent = title;
    $("dialog-description").textContent = description;
    $("record-detail").hidden = mode !== "detail";
    $("record-form").hidden = mode !== "edit";
    $("delete-confirm").hidden = mode !== "delete";
    if (!dialog.open) dialog.showModal();
    if (mode === "edit") $("record-title").focus();
    else if (mode === "delete") $("cancel-delete").focus();
    else $("close-dialog").focus();
}

function addField(label, value, wide = false) {
    if (!value) return;
    const field = element("div", wide ? "wide" : "");
    field.append(element("dt", "", label), element("dd", "", value));
    $("detail-fields").append(field);
}

function openRecord(id) {
    const record = snapshot?.records.find(item => item.id === id);
    if (!record) {
        notice("Esse registro não está mais disponível. Atualize a lista.", true);
        return;
    }
    clearDialog();
    current = { record, revision: snapshot.revision };
    addField("Telefone", record.phone);
    addField("Data importante", record.date ? formatDate(record.date) : "");
    addField("Notas", record.notes, true);
    if (!$("detail-fields").childElementCount) addField("Informações adicionais", "Nenhuma.", true);
    $("detail-attachment").hidden = !record.attachment;
    if (record.attachment) {
        $("attachment-name").textContent = record.attachment.name;
        $("view-attachment").hidden = !previewType(record.attachment);
        if ($("view-attachment").hidden && /\.pdf$/iu.test(record.attachment.name)) {
            message("attachment-feedback", "Baixe o PDF para abrir em um leitor.");
        }
    }
    showDialogMode("detail", record.title, record.category);
}

function openEditor(record) {
    if (!snapshot) return;
    clearPreview();
    message("record-feedback");
    current = { record, revision: snapshot.revision };
    $("record-form").reset();
    $("record-title").value = record?.title || "";
    $("record-category").value = record?.category || snapshot.categories[0] || "";
    $("record-phone").value = record?.phone || "";
    $("record-date").value = record?.date || "";
    $("record-notes").value = record?.notes || "";
    showDialogMode("edit", record ? "Editar registro" : "Novo registro");
}

function cancelEditor() {
    if (current?.record) openRecord(current.record.id);
    else dialog.close();
}

function confirmDeletion() {
    if (!current?.record) return;
    clearPreview();
    current.revision = snapshot.revision;
    message("delete-feedback");
    showDialogMode("delete", "Excluir registro?", current.record.title);
}

function isCurrent(epoch, editing) {
    return generation === epoch && current === editing && dialog.open;
}

async function mutate(path, payload, buttonId, feedbackId, success) {
    const editing = current;
    const epoch = generation;
    $(buttonId).disabled = true;
    message(feedbackId, "Gravando no celular…");
    try {
        const data = await request(path, { body: payload, timeout: 20000 });
        if (epoch !== generation) return;
        applySnapshot(data);
        if (isCurrent(epoch, editing)) dialog.close();
        notice(success);
    } catch (error) {
        if (epoch !== generation) return;
        if (error.status === 409) {
            await refreshRecords();
            if (isCurrent(epoch, editing)) {
                message(feedbackId, "O cofre mudou no celular. Confira a versão atual do registro antes de tentar novamente.", "error");
            }
        } else {
            report(error, text => {
                if (isCurrent(epoch, editing)) message(feedbackId, text, "error");
                else notice(text, true);
            });
            if (error.status === 0) await refreshRecords();
        }
    } finally {
        if (isCurrent(epoch, editing)) $(buttonId).disabled = false;
    }
}

function saveRecord(event) {
    event.preventDefault();
    if (!current || $("save-record").disabled) return;
    const title = $("record-title").value.trim();
    if (!title) {
        message("record-feedback", "Digite um título para o registro.", "error");
        $("record-title").focus();
        return;
    }
    const body = {
        revision: current.revision,
        title,
        category: $("record-category").value,
        notes: $("record-notes").value.trim(),
        phone: $("record-phone").value.trim(),
        date: $("record-date").value
    };
    if (current.record) body.id = current.record.id;
    void mutate("/api/record", body, "save-record", "record-feedback", "Registro salvo no cofre.");
}

function previewType(attachment) {
    const mime = String(attachment.mime || "").toLowerCase().split(";")[0].trim();
    if (rasterTypes.has(mime)) return mime;
    if (mime.startsWith("text/")) return "text/plain";
    return "";
}

function download(blob, name) {
    const safeName = String(name || "arquivo").replace(/[\p{Cc}\p{Cf}/\\]/gu, "_").trim() || "arquivo";
    const url = retainBlob(new Blob([blob], { type: "application/octet-stream" }));
    const link = element("a");
    link.href = url;
    link.download = safeName;
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => releaseBlob(url), 1000);
}

async function createPreview(blob, attachment) {
    const mime = previewType(attachment);
    if (mime === "text/plain") {
        const begin = await blob.slice(0, 65536).text();
        return element("pre", "", begin + (blob.size > 65536 ? "\n\n[Apenas o início do arquivo é exibido.]" : ""));
    }
    const image = element("img");
    image.alt = attachment.name;
    return image;
}

async function attachmentAction(preview = false) {
    if (!current?.record?.attachment || (preview && !previewType(current.record.attachment))) return;
    if (preview && !$("attachment-preview").hidden) {
        clearPreview();
        return;
    }
    const item = current;
    const epoch = generation;
    const attachment = item.record.attachment;
    const buttonId = preview ? "view-attachment" : "download-attachment";
    $(buttonId).disabled = true;
    message("attachment-feedback", "Lendo anexo…");
    try {
        const blob = await request("/api/attachment?id=" + encodeURIComponent(item.record.id), { binary: true, timeout: 20000 });
        if (!isCurrent(epoch, item)) return;
        if (!preview) {
            download(blob, attachment.name);
            message("attachment-feedback", "O download cria uma cópia sem a criptografia do cofre.");
            return;
        }
        const content = await createPreview(blob, attachment);
        if (!isCurrent(epoch, item)) return;
        if (content.tagName === "IMG") {
            previewUrl = retainBlob(new Blob([blob], { type: previewType(attachment) }));
            content.src = previewUrl;
        }
        $("attachment-preview").replaceChildren(content);
        $("attachment-preview").hidden = false;
        $("view-attachment").textContent = "Fechar prévia";
        message("attachment-feedback");
    } catch (error) {
        if (isCurrent(epoch, item)) report(error, text => message("attachment-feedback", text, "error"));
    } finally {
        if (isCurrent(epoch, item)) $(buttonId).disabled = false;
    }
}

async function downloadBackup() {
    if ($("backup-download").disabled) return;
    const epoch = generation;
    $("backup-download").disabled = true;
    message("backup-feedback", "Preparando o arquivo criptografado…");
    try {
        const backup = await request("/api/backup", { binary: true, timeout: 35000 });
        if (epoch !== generation) return;
        const date = new Intl.DateTimeFormat("sv-SE").format(new Date());
        download(backup, "arca-backup-" + date + ".arca");
        message("backup-feedback", "O navegador recebeu o backup criptografado.");
    } catch (error) {
        if (epoch === generation) report(error, text => message("backup-feedback", text, "error"));
    } finally {
        if (epoch === generation) $("backup-download").disabled = false;
    }
}

async function pair(event) {
    event.preventDefault();
    if ($("pair-submit").disabled) return;
    const code = $("pair-code").value.replace(/[\s-]/gu, "").toUpperCase();
    if (!/^[0-9A-HJKMNP-TV-Z]{16}$/u.test(code)) {
        message("pair-feedback", "Informe os 16 caracteres exibidos no celular.", "error");
        $("pair-code").focus();
        return;
    }
    const epoch = generation;
    $("pair-submit").disabled = true;
    message("pair-feedback", "Conectando ao celular…");
    try {
        await request("/api/pair", { body: { code } });
        const data = await request("/api/state");
        if (epoch === generation) showDashboard(data);
    } catch (error) {
        if (epoch === generation) message("pair-feedback", error.message, "error");
    } finally {
        if (epoch === generation) $("pair-submit").disabled = false;
    }
}

async function boot() {
    const epoch = generation;
    $("pair-submit").disabled = true;
    try {
        const data = await request("/api/state", { timeout: 5000 });
        if (epoch === generation) showDashboard(data);
    } catch (error) {
        if (epoch === generation) message("pair-feedback", error.status === 401 ? "" : error.message, error.status === 401 ? "" : "error");
    } finally {
        if (epoch === generation) $("pair-submit").disabled = false;
    }
}

async function checkSession() {
    if (!connected || healthCheck) return;
    healthCheck = true;
    const epoch = generation;
    try {
        const status = await request("/api/status", { timeout: 4500 });
        if (epoch === generation && !status.authenticated) unpair("A sessão terminou no celular.");
    } catch {
        if (epoch === generation) unpair("A conexão com o celular foi interrompida.");
    } finally {
        if (epoch === generation) healthCheck = false;
    }
}

async function logout() {
    if ($("logout").disabled) return;
    const epoch = generation;
    $("logout").disabled = true;
    try {
        await request("/api/logout", { body: {}, timeout: 5000 });
        if (epoch === generation) unpair("Sessão encerrada.", false);
    } catch (error) {
        if (epoch !== generation) return;
        if (error.status === 401) unpair("Sessão encerrada.", false);
        else unpair("O celular ficou inacessível antes de confirmar o encerramento. Desative o acesso pelo navegador no app.");
    }
}

for (const button of sectionButtons) button.addEventListener("click", () => navigate(button.dataset.view));
$("pair-form").addEventListener("submit", pair);
$("logout").addEventListener("click", logout);
$("new-record").addEventListener("click", () => openEditor(null));
$("refresh-records").addEventListener("click", () => refreshRecords(true));
$("record-search").addEventListener("input", renderRecords);
$("category-filter").addEventListener("change", event => selectCategory(event.target.value));
$("all-records").addEventListener("click", resetFilters);
$("attached-records").addEventListener("click", () => {
    category = "";
    attachmentsOnly = !attachmentsOnly;
    renderRecords();
});
$("list-action").addEventListener("click", () => listAction?.());
$("backup-download").addEventListener("click", downloadBackup);
$("close-dialog").addEventListener("click", () => dialog.close());
dialog.addEventListener("close", () => { if (!dialog.open) clearDialog(); });
$("edit-record").addEventListener("click", () => {
    const record = snapshot?.records.find(item => item.id === current?.record?.id);
    if (record) openEditor(record);
});
$("cancel-edit").addEventListener("click", cancelEditor);
$("record-form").addEventListener("submit", saveRecord);
$("delete-record").addEventListener("click", confirmDeletion);
$("cancel-delete").addEventListener("click", () => openRecord(current.record.id));
$("confirm-delete").addEventListener("click", () => {
    if (!current?.record || $("confirm-delete").disabled) return;
    void mutate("/api/delete", { id: current.record.id, revision: current.revision }, "confirm-delete",
        "delete-feedback", "Registro excluído do cofre.");
});
$("view-attachment").addEventListener("click", () => attachmentAction(true));
$("download-attachment").addEventListener("click", () => attachmentAction());
document.addEventListener("visibilitychange", () => { if (!document.hidden) void checkSession(); });
window.addEventListener("pagehide", clearSessionView);
window.addEventListener("pageshow", event => { if (event.persisted) void boot(); });
window.setInterval(checkSession, 8000);
void boot();
