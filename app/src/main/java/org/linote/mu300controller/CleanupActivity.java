package org.linote.mu300controller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Local-only cleanup. Unfinished/unknown recovery records and ADB authorization keys are never deleted. */
public final class CleanupActivity extends Activity {
    private static final int REQ_EXPORT = 41;
    private static final int BG = 0xfff4f7f7, CARD = 0xffffffff, INK = 0xff18282f;
    private static final int MUTED = 0xff5d6f77, TEAL = 0xff087e78, RED = 0xffab3139;

    private static final class FileEntry {
        final File file;
        final String zipName;
        FileEntry(File file, String zipName) { this.file = file; this.zipName = zipName; }
    }
    private static final class RecordBackup {
        final File directory;
        final List<FileEntry> files;
        final long bytes;
        RecordBackup(File directory, List<FileEntry> files, long bytes) { this.directory = directory; this.files = files; this.bytes = bytes; }
    }
    private static final class Category {
        final String title, note;
        final List<File> roots = new ArrayList<>();
        final boolean backup;
        long bytes;
        boolean checked;
        CheckBox box;
        Category(String title, String note, boolean backup) { this.title = title; this.note = note; this.backup = backup; }
    }

    private LinearLayout body;
    private TextView status;
    private final List<Category> categories = new ArrayList<>();
    private final List<RecordBackup> completed = new ArrayList<>();
    private final List<File> protectedRecords = new ArrayList<>();
    private Category backupCategory, cacheCategory, logCategory;
    private LinkedHashMap<String,String> exportedHashes;
    private boolean exportVerified, working;
    private String exportName = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(BG);
        LinearLayout root = column(); root.setBackgroundColor(BG);
        LinearLayout head = row(); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(8), dp(8), dp(16), dp(8));
        TextView back = text("←", 20, INK, true); back.setGravity(Gravity.CENTER); back.setContentDescription("返回");
        back.setOnClickListener(v -> finish()); head.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text("清理本机占用", 17, INK, true); title.setPadding(dp(6), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(head, matchWrap());
        View divider = new View(this); divider.setBackgroundColor(0xffdfe7e7); root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        ScrollView scroll = new ScrollView(this); body = column(); body.setPadding(dp(20), dp(18), dp(20), dp(28)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        scan();
    }

    private void scan() {
        categories.clear(); completed.clear(); protectedRecords.clear();
        List<FileEntry> installed = new ArrayList<>(); long installedBytes = 0;
        File root = new File(getFilesDir(), "make-room");
        if (root.isDirectory() && !isLink(root)) {
            File[] dirs = root.listFiles();
            if (dirs != null) for (File dir : dirs) {
                if (!dir.isDirectory() || isLink(dir)) { protectedRecords.add(dir); continue; }
                File tx = new File(dir, "transaction.json");
                try {
                    if (!tx.isFile() || isLink(tx)) throw new IOException("missing/unsafe transaction");
                    JSONObject j = new JSONObject(readText(tx));
                    if (!"installed".equals(j.optString("state", ""))) { protectedRecords.add(dir); continue; }
                    List<FileEntry> entries = new ArrayList<>();
                    long bytes = collectRecordFiles(dir, dir, entries);
                    if (entries.isEmpty()) { protectedRecords.add(dir); continue; }
                    RecordBackup b = new RecordBackup(dir, entries, bytes); completed.add(b); installedBytes = Math.addExact(installedBytes, bytes);
                    installed.addAll(entries);
                } catch (Exception e) { protectedRecords.add(dir); }
            }
        }
        backupCategory = new Category("已完成恢复记录与备份", "仅包含 transaction.json 明确标记为 installed 的记录。删除前必须导出 ZIP 并回读校验；未完成、状态未知或读取异常的记录会始终保留。导出包可能含设备序列号 / CID，请妥善保管。", true);
        backupCategory.bytes = installedBytes;
        for (RecordBackup r : completed) backupCategory.roots.add(r.directory);
        cacheCategory = new Category("下载 ZIP 与临时缓存", "删除后需重新下载官方 ZIP；已验证的恢复事务原始 ZIP 在另一受保护目录，不受影响。", false);
        cacheCategory.roots.add(getCacheDir()); cacheCategory.bytes = safeSize(getCacheDir());
        logCategory = new Category("运行日志", "删除控制手机本应用的诊断日志；以后需要排查问题时可重新生成。", false);
        logCategory.roots.add(new File(getFilesDir(), "mu300-runtime.log")); logCategory.bytes = safeSize(logCategory.roots.get(0));
        categories.add(backupCategory); categories.add(cacheCategory); categories.add(logCategory);
        build();
    }

    private long collectRecordFiles(File file, File recordRoot, List<FileEntry> out) throws IOException {
        if (isLink(file)) throw new IOException("symbolic link in recovery record");
        String rootPath = recordRoot.getCanonicalPath() + File.separator;
        String canonical = file.getCanonicalPath();
        if (!canonical.startsWith(rootPath) && !canonical.equals(recordRoot.getCanonicalPath())) throw new IOException("path escaped transaction directory");
        if (file.isFile()) {
            String rel = recordRoot.getName() + "/" + recordRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            out.add(new FileEntry(file, "make-room/" + rel)); return file.length();
        }
        if (!file.isDirectory()) throw new IOException("unsupported transaction entry");
        File[] kids = file.listFiles(); if (kids == null) throw new IOException("cannot enumerate transaction");
        long n = 0; for (File kid : kids) n = Math.addExact(n, collectRecordFiles(kid, recordRoot, out)); return n;
    }

    private void build() {
        body.removeAllViews();
        addText("只清理这台控制手机上本应用的缓存、日志或已完成恢复资料；不会连接或改动目标设备。", 14, MUTED, false, 0, 10);
        if (!protectedRecords.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (File f : protectedRecords) {
                if (b.length() > 0) b.append('\n');
                b.append(f == null ? "无法识别的记录" : f.getName());
            }
            addNotice("保留中的恢复记录", "未完成、未知状态或读取异常的记录及其备份不计入可清理大小，也不会删除：\n" + b, 0);
        }
        long total = 0;
        for (Category c : categories) {
            total = Math.addExact(total, c.bytes);
            LinearLayout card = card(); c.box = new CheckBox(this);
            c.box.setText(c.title + " · " + human(c.bytes)); c.box.setTextColor(INK); c.box.setTextSize(14.5f);
            c.box.setButtonTintList(ColorStateList.valueOf(c.backup ? RED : TEAL)); c.box.setChecked(c.checked);
            c.box.setEnabled(!working && c.bytes > 0); c.box.setOnCheckedChangeListener((v, checked) -> c.checked = checked);
            card.addView(c.box, matchWrap()); addText(card, c.note, 12.5f, MUTED, false, 5, 0); body.addView(card, topMatch(10));
        }
        LinearLayout protect = card(); addText(protect, "始终保留的内容", 14.5f, INK, true, 0, 4);
        addText(protect, "未完成或状态无法确认的恢复事务、其备份与原始 ZIP，以及 ADB 授权密钥（adbkey / adbkey.pub）不会删除。", 12.5f, MUTED, false, 0, 0);
        body.addView(protect, topMatch(10));
        addText("可清理合计：" + human(total), 14, INK, true, 10, 8);
        TextView export = button("导出已完成恢复记录为 ZIP（推荐先做）", 1); export.setOnClickListener(v -> exportZip());
        export.setEnabled(!working && !completed.isEmpty()); body.addView(export, topMatch(8));
        status = text(exportVerified ? "✓ 已导出并校验：" + exportName + "。现在可以删除已完成恢复记录。"
                : "已完成恢复记录须先导出 ZIP 并校验通过，才能删除。ZIP 含 SHA256SUMS 和 info.json。", 12.5f,
                exportVerified ? TEAL : MUTED, false); body.addView(status, topMatch(8));
        TextView del = button("删除所选", 2); del.setOnClickListener(v -> confirmDelete()); del.setEnabled(!working); body.addView(del, topMatch(8));
    }

    private void exportZip() {
        if (completed.isEmpty() || working) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/zip");
        String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
        i.putExtra(Intent.EXTRA_TITLE, "mu300-otg-backup-" + ts + ".zip");
        try { startActivityForResult(i, REQ_EXPORT); } catch (RuntimeException e) { toast("无法打开系统文件选择器。备份未删除。"); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); working = true; exportVerified = false; build(); status.setText("正在导出并校验 ZIP……");
        new Thread(() -> {
            boolean ok = false; String label = ""; String error = null;
            try {
                int n = writeZip(uri); verifyZip(uri); ok = true;
                label = (uri.getLastPathSegment() == null ? uri.toString() : uri.getLastPathSegment()) + "（" + n + " 个文件）";
            } catch (Exception e) { error = "导出或回读校验失败：" + safe(e) + "。本机资料未删除。"; }
            final boolean verified = ok; final String name = label, failure = error;
            runOnUiThread(() -> {
                working = false; exportVerified = verified; if (verified) { exportName = name; exportedHashes = new LinkedHashMap<>(manifest); } build();
                if (failure != null) { status.setText(failure); status.setTextColor(RED); }
            });
        }, "local-cleanup-export").start();
    }

    private LinkedHashMap<String,String> manifest;
    private int writeZip(Uri uri) throws Exception {
        List<FileEntry> files = exportFiles(); manifest = new LinkedHashMap<>(); byte[] buf = new byte[65536];
        OutputStream raw = getContentResolver().openOutputStream(uri, "w");
        if (raw == null) throw new IOException("无法写入所选位置");
        try (ZipOutputStream z = new ZipOutputStream(raw)) {
            for (FileEntry e : files) {
                if (!insideAppFiles(e.file)) throw new IOException("恢复记录路径超出本应用目录");
                MessageDigest md = sha256(); z.putNextEntry(new ZipEntry(e.zipName));
                try (InputStream in = new FileInputStream(e.file)) {
                    for (int n; (n = in.read(buf)) > 0; ) { md.update(buf, 0, n); z.write(buf, 0, n); }
                }
                z.closeEntry(); manifest.put(e.zipName, hex(md.digest()));
            }
            z.putNextEntry(new ZipEntry("SHA256SUMS")); z.write(checksumManifest().getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            JSONObject info = new JSONObject(); info.put("app", "MU300 Linux OTG 控制器"); info.put("app_version", BuildInfo.VERSION_NAME);
            info.put("created", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new java.util.Date()));
            info.put("files", manifest.size()); z.putNextEntry(new ZipEntry("info.json")); z.write(info.toString(2).getBytes(StandardCharsets.UTF_8)); z.closeEntry();
        }
        return manifest.size();
    }

    private void verifyZip(Uri uri) throws IOException {
        LinkedHashMap<String,String> got = new LinkedHashMap<>(); String sumsText = null, infoText = null;
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("无法重新读取已导出的 ZIP");
        try (ZipInputStream z = new ZipInputStream(raw)) {
            byte[] buf = new byte[65536];
            for (ZipEntry entry; (entry = z.getNextEntry()) != null; ) {
                MessageDigest md = sha256(); ByteArrayOutputStream meta = null;
                if ("SHA256SUMS".equals(entry.getName()) || "info.json".equals(entry.getName())) meta = new ByteArrayOutputStream();
                for (int n; (n = z.read(buf)) > 0; ) { md.update(buf, 0, n); if (meta != null) meta.write(buf, 0, n); }
                if ("SHA256SUMS".equals(entry.getName())) {
                    if (sumsText != null) throw new IOException("ZIP 含重复 SHA256SUMS");
                    sumsText = new String(meta.toByteArray(), StandardCharsets.UTF_8);
                } else if ("info.json".equals(entry.getName())) {
                    if (infoText != null) throw new IOException("ZIP 含重复 info.json");
                    infoText = new String(meta.toByteArray(), StandardCharsets.UTF_8);
                } else if (got.put(entry.getName(), hex(md.digest())) != null) throw new IOException("ZIP 含重复文件名");
            }
        }
        if (sumsText == null || !checksumManifest().equals(sumsText) || infoText == null || !got.equals(manifest))
            throw new IOException("ZIP 缺少校验清单或回读哈希与导出内容不一致");
        try {
            JSONObject info = new JSONObject(infoText);
            if (!"MU300 Linux OTG 控制器".equals(info.optString("app", ""))
                    || !BuildInfo.VERSION_NAME.equals(info.optString("app_version", ""))
                    || info.optInt("files", -1) != manifest.size()) throw new IOException("ZIP 元数据与当前导出不匹配");
        } catch (org.json.JSONException e) { throw new IOException("ZIP 元数据无法解析"); }
    }

    private String checksumManifest() {
        StringBuilder sums = new StringBuilder();
        for (Map.Entry<String,String> e : manifest.entrySet()) sums.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
        return sums.toString();
    }

    private List<FileEntry> exportFiles() {
        List<FileEntry> files = new ArrayList<>(); for (RecordBackup r : completed) files.addAll(r.files); return files;
    }

    private void confirmDelete() {
        if (working) return;
        List<Category> selected = new ArrayList<>(); long bytes = 0; boolean backups = false;
        for (Category c : categories) if (c.checked && c.bytes > 0) { selected.add(c); bytes = Math.addExact(bytes, c.bytes); backups |= c.backup; }
        if (selected.isEmpty()) { toast("请先勾选要删除的内容"); return; }
        if (backups && !exportVerified) { new AlertDialog.Builder(this).setTitle("请先导出备份").setMessage("已完成恢复记录和分区备份删除后无法从本机恢复。请先导出 ZIP 并通过回读校验。未完成记录不会删除。").setPositiveButton("知道了", null).show(); return; }
        StringBuilder m = new StringBuilder("只删除本机应用目录内所选内容：\n");
        for (Category c : selected) m.append("• ").append(c.title).append("（").append(human(c.bytes)).append("）\n");
        m.append("\n未完成/未知恢复记录及 ADB 授权密钥会保留。");
        if (backups) m.append("\n已导出并校验：").append(exportName).append("\n请输入“删除备份”确认：");
        EditText input = new EditText(this); AlertDialog.Builder d = new AlertDialog.Builder(this).setTitle("确认清理 " + human(bytes)).setMessage(m.toString());
        if (backups) d.setView(input);
        final boolean needsWord = backups;
        d.setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
            if (needsWord && !"删除备份".equals(input.getText().toString().trim())) { toast("输入不正确，未删除"); return; }
            deleteSelected(selected);
        }).show();
    }

    private void deleteSelected(List<Category> selected) {
        if (working) return; working = true; build();
        new Thread(() -> {
            long freed = 0; String error = null;
            try {
                for (Category c : selected) {
                    if (c == backupCategory) {
                        if (!exportVerified || exportedHashes == null) throw new IOException("备份导出状态丢失；拒绝删除");
                        for (RecordBackup record : completed) {
                            if (!recordMatchesExport(record)) throw new IOException("已完成记录在导出后发生变化；未删除该备份：" + record.directory.getName());
                            freed += deleteTree(record.directory);
                        }
                    } else if (c == cacheCategory) {
                        freed += deleteChildren(getCacheDir());
                    } else if (c == logCategory) {
                        freed += AppRuntimeLog.clear(this);
                    }
                }
            } catch (Exception e) { error = safe(e); }
            final long bytes = freed; final String failure = error;
            runOnUiThread(() -> {
                working = false; Toast.makeText(this, failure == null ? "已释放 " + human(bytes) : "清理已部分完成：" + failure, Toast.LENGTH_LONG).show();
                exportVerified = false; exportedHashes = null; scan();
            });
        }, "local-cleanup-delete").start();
    }

    private boolean recordMatchesExport(RecordBackup record) throws Exception {
        File tx = new File(record.directory, "transaction.json");
        JSONObject j = new JSONObject(readText(tx)); if (!"installed".equals(j.optString("state", ""))) return false;
        List<FileEntry> now = new ArrayList<>(); collectRecordFiles(record.directory, record.directory, now);
        if (now.size() != record.files.size()) return false;
        for (FileEntry e : now) {
            String expected = exportedHashes.get(e.zipName);
            if (expected == null || !expected.equals(hashFile(e.file))) return false;
        }
        return true;
    }

    private long deleteChildren(File dir) throws IOException {
        if (dir == null || !dir.isDirectory() || isLink(dir)) return 0;
        long n = 0; File[] files = dir.listFiles(); if (files == null) throw new IOException("无法读取缓存目录");
        for (File f : files) n = Math.addExact(n, deleteTree(f)); return n;
    }
    private long deleteTree(File f) throws IOException {
        if (f == null || !f.exists()) return 0;
        if (isLink(f)) return 0;
        if (f.isFile()) { long n = f.length(); if (!f.delete()) throw new IOException("无法删除 " + f.getName()); return n; }
        if (!f.isDirectory()) return 0;
        long n = 0; File[] kids = f.listFiles(); if (kids == null) throw new IOException("无法遍历 " + f.getName());
        for (File child : kids) n = Math.addExact(n, deleteTree(child));
        if (!f.equals(getCacheDir()) && f.list() != null && f.list().length == 0) f.delete();
        return n;
    }

    private boolean insideAppFiles(File f) throws IOException {
        String root = getFilesDir().getCanonicalPath() + File.separator;
        return f.getCanonicalPath().startsWith(root) && !isLink(f);
    }
    private static boolean isLink(File f) {
        try { return Files.isSymbolicLink(f.toPath()); } catch (RuntimeException e) { return true; }
    }
    private long safeSize(File f) {
        if (f == null || !f.exists() || isLink(f)) return 0;
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles(); if (kids == null) return 0;
        long n = 0; for (File c : kids) n += safeSize(c); return n;
    }
    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; for (int n; (n = in.read(b)) != -1; ) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String hashFile(File f) throws IOException {
        MessageDigest md = sha256(); try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[65536]; for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n); }
        return hex(md.digest());
    }
    private static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 255)); return s.toString(); }
    private static String safe(Exception e) { String m = e.getMessage(); return m == null ? e.getClass().getSimpleName() : m; }
    private static String human(long b) { if (b < (1L << 20)) return String.format(Locale.US, "%.1f KiB", b / 1024.0); if (b < (1L << 30)) return String.format(Locale.US, "%.1f MiB", b / 1048576.0); return String.format(Locale.US, "%.2f GiB", b / 1073741824.0); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout card() { LinearLayout l = column(); l.setPadding(dp(14), dp(12), dp(14), dp(12)); l.setBackgroundColor(CARD); return l; }
    private TextView text(String s, float size, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD); t.setLineSpacing(dp(3), 1.03f); return t; }
    private void addText(String s, float size, int color, boolean bold, int top, int bottom) { addText(body, s, size, color, bold, top, bottom); }
    private void addText(LinearLayout p, String s, float size, int color, boolean bold, int top, int bottom) { TextView t = text(s, size, color, bold); LinearLayout.LayoutParams lp = matchWrap(); lp.topMargin = dp(top); lp.bottomMargin = dp(bottom); p.addView(t, lp); }
    private void addNotice(String title, String message, int tint) { LinearLayout c = card(); addText(c, title, 14, tint == 0 ? INK : RED, true, 0, 4); addText(c, message, 12.5f, MUTED, false, 0, 0); body.addView(c, topMatch(10)); }
    private TextView button(String label, int kind) { TextView t = text(label, 14, kind == 2 ? RED : TEAL, true); t.setGravity(Gravity.CENTER); t.setPadding(dp(12), dp(12), dp(12), dp(12)); t.setBackgroundColor(CARD); t.setClickable(true); t.setFocusable(true); return t; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams topMatch(int top) { LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(top); return p; }
    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
}
