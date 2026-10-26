package org.linote.mu300controller;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Android USB-host adapter for the upstream tools/android-web.sh protocol. */
final class WebInstallerClient {
    static final String STAGE = "/data/local/tmp/mu300-web";
    private static final String ASSET_DIR = "upstream/device/";
    private static final String[] FILES = {"android-web.sh", "storage.sh", "android-boot-image.sh",
            "android-mount-mu300root.sh", "switch.sh"};
    private static final long MAX_SCRIPT_BYTES = 256 * 1024;

    interface LineListener { void onLine(String line, InstallerProtocol.Event event); }

    static final class ZipManifest {
        final String name, tag, system, os, ubuntu, kernel;
        final boolean web;
        ZipManifest(String name, String tag, String system, String os, String ubuntu, String kernel, boolean web) {
            this.name = name; this.tag = tag; this.system = system; this.os = os; this.ubuntu = ubuntu; this.kernel = kernel; this.web = web;
        }
    }

    private final Context context;
    private final AdbConnection conn;

    WebInstallerClient(Context context, AdbConnection conn) { this.context = context.getApplicationContext(); this.conn = conn; }

    void stage() throws Exception {
        AssetManager assets = context.getAssets();
        String sums = readAssetText(assets, ASSET_DIR + "SHA256SUMS", 8192);
        Map<String, String> expected = parseSums(sums);
        if (expected.size() != FILES.length) throw new IllegalStateException("内嵌上游 helper 校验清单不完整");
        List<File> staged = new ArrayList<>();
        try {
            for (String name : FILES) {
                String hash = expected.get(name);
                if (!InstallerProtocol.safeArgument(name) || !InstallerProtocol.safeSha256(hash))
                    throw new IllegalStateException("内嵌 helper 文件名或 SHA-256 清单异常：" + name);
                byte[] bytes = readAsset(assets, ASSET_DIR + name, MAX_SCRIPT_BYTES);
                if (!hash.equals(sha256(bytes))) throw new IllegalStateException("APK 内的上游脚本校验失败：" + name);
                File file = new File(context.getCacheDir(), "mu300-web-" + name);
                try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(bytes); out.flush(); }
                staged.add(file);
            }
            List<String> lines = new ArrayList<>();
            int rc = conn.shell("mkdir -p " + STAGE + "/in && [ ! -L " + STAGE + " ] && [ ! -L " + STAGE + "/in ] && chmod 755 " + STAGE + " && echo MU300_STAGE_OK",
                    lines::add, 30000);
            if (rc != 0 || !contains(lines, "MU300_STAGE_OK")) throw new IllegalStateException("无法建立安全的上游 helper 暂存目录");
            File[] files = staged.toArray(new File[0]);
            String[] remote = new String[FILES.length]; int[] modes = new int[FILES.length];
            for (int i = 0; i < FILES.length; i++) { remote[i] = STAGE + "/" + FILES[i]; modes[i] = 0644; }
            conn.push(files, remote, modes, null);
            StringBuilder verify = new StringBuilder("sha256sum");
            for (String name : FILES) verify.append(' ').append(STAGE).append('/').append(name);
            List<String> remoteSums = new ArrayList<>();
            int verifyRc = conn.shell("su -c " + shellQuote(verify.toString()), remoteSums::add, 60000);
            if (verifyRc != 0) throw new IllegalStateException("目标端无法验证内嵌上游 helper 的 SHA-256");
            Map<String,String> got = new HashMap<>();
            for (String line : remoteSums) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*([0-9a-f]{64})\\s+(.+)$").matcher(line.trim());
                if (m.matches()) got.put(m.group(2), m.group(1));
            }
            for (String name : FILES) if (!expected.get(name).equals(got.get(STAGE + "/" + name)))
                throw new IllegalStateException("目标端 helper 校验不匹配：" + name);
            // The staged scripts remain unprivileged inputs, just as in the upstream web installer.
            for (File f : staged) if (f.exists() && !f.delete()) f.deleteOnExit();
        } finally {
            for (File f : staged) if (f.exists()) f.delete();
        }
    }

    JSONObject status(boolean deep) throws Exception {
        List<String> lines = invoke(deep ? new String[]{"status", "deep"} : new String[]{"status"}, null, null, 120000);
        String payload = firstPayload(lines, "status");
        if (payload == null) throw new IllegalStateException("上游 helper 未返回设备状态");
        JSONObject o;
        try { o = new JSONObject(payload); } catch (JSONException e) { throw new IllegalStateException("上游 helper 状态 JSON 无法解析"); }
        if (o.optInt("v", -1) != 1 || !o.has("region") || !o.has("job") || !o.has("parts"))
            throw new IllegalStateException("上游 helper 返回了不兼容的设备状态格式");
        return o;
    }

    ZipManifest take(File zip, String name, String sha256, AdbConnection.Progress progress, Runnable onUploaded) throws Exception {
        if (!InstallerProtocol.safeZipName(name) || !InstallerProtocol.safeSha256(sha256))
            throw new IllegalArgumentException("ZIP 文件名或 SHA-256 不符合上游协议");
        File inbox = new File(context.getCacheDir(), "mu300-web-in-" + name);
        try {
            try (InputStream in = new java.io.FileInputStream(zip); FileOutputStream out = new FileOutputStream(inbox, false)) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            int rc = conn.shell("mkdir -p " + STAGE + "/in && [ ! -L " + STAGE + " ] && [ ! -L " + STAGE + "/in ] && echo MU300_IN_OK", null, 30000);
            if (rc != 0) throw new IllegalStateException("无法准备 ZIP 输入目录");
            conn.push(new File[]{inbox}, new String[]{STAGE + "/in/" + name}, new int[]{0644}, progress);
            if (onUploaded != null) onUploaded.run();
            List<String> lines = invoke(new String[]{"take", name, sha256}, null, null, 600000);
            String payload = firstPayload(lines, "zip");
            if (payload == null) throw new IllegalStateException("上游 helper 未返回 ZIP manifest");
            JSONObject j;
            try { j = new JSONObject(payload); } catch (JSONException e) { throw new IllegalStateException("上游 ZIP manifest 无法解析"); }
            ZipManifest z = new ZipManifest(j.optString("name", ""), j.optString("tag", ""), j.optString("system", ""),
                    j.optString("os", ""), j.optString("ubuntu", ""), j.optString("kernel", ""), j.optInt("web", 0) == 1);
            if (!name.equals(z.name) || !z.web || z.tag.isEmpty() || z.system.isEmpty() || z.kernel.isEmpty())
                throw new IllegalStateException("所选 ZIP 不含兼容的网页/OTG 安装协议 manifest");
            return z;
        } finally { if (inbox.exists()) inbox.delete(); }
    }

    void setAnswers(String text, String password) throws Exception {
        if (text == null || text.length() > 65536 || text.contains("MU300_PASSWORD="))
            throw new IllegalArgumentException("答案文件不符合上游格式或包含明文密码");
        File file = new File(context.getCacheDir(), "mu300-answers-" + System.nanoTime());
        try {
            try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.flush(); }
            conn.push(new File[]{file}, new String[]{STAGE + "/in/answers.conf"}, new int[]{0600}, null);
            invoke(new String[]{"answers"}, null, null, 30000);
        } finally { if (file.exists()) file.delete(); }
        if (password != null && !password.isEmpty()) {
            if (password.length() < 6 || password.length() > 128 || password.contains("\n") || password.contains("\r"))
                throw new IllegalArgumentException("密码需为 6–128 个字符，且不能包含换行");
            byte[] stdin = (password + "\n").getBytes(StandardCharsets.UTF_8);
            invoke(new String[]{"password"}, stdin, null, 30000);
            java.util.Arrays.fill(stdin, (byte) 0);
        }
    }

    void start(String kind, String name) throws Exception {
        if (!("plan".equals(kind) || "install".equals(kind)) || !InstallerProtocol.safeZipName(name))
            throw new IllegalArgumentException("上游 job 参数不合法");
        List<String> lines = invoke(new String[]{"run", kind, name}, null, null, 60000);
        if (!containsPrefix(lines, "MU300-WEB-JOB started " + kind))
            throw new IllegalStateException("上游 helper 未确认已启动 " + kind + " 任务");
    }

    int follow(LineListener listener) throws Exception {
        final int[] rc = {-1};
        invoke(new String[]{"follow"}, null, (line, event) -> {
            if ("exit".equals(event.type)) rc[0] = event.rc;
            if (listener != null) listener.onLine(line, event);
        }, 0);
        if (rc[0] == -1) throw new IllegalStateException("上游任务没有返回终态退出码");
        return rc[0];
    }

    void cleanAnswers() throws Exception { invoke(new String[]{"clean"}, null, null, 120000); }

    List<String> invoke(String[] args, byte[] stdin, LineListener listener, int timeoutMs) throws Exception {
        StringBuilder command = new StringBuilder("sh ").append(STAGE).append("/android-web.sh");
        for (String arg : args) {
            if (!InstallerProtocol.safeArgument(arg)) throw new IllegalArgumentException("不安全的 helper 参数");
            command.append(' ').append(arg);
        }
        List<String> lines = new ArrayList<>();
        LineListener sink = (line, event) -> {
            lines.add(line);
            if (listener != null) listener.onLine(line, event);
        };
        String remote = "su -c " + shellQuote(command.toString());
        int rc = stdin == null ? conn.shell(remote, line -> sink.onLine(line, InstallerProtocol.parse(line)), timeoutMs)
                : conn.shellInput(remote, stdin, line -> sink.onLine(line, InstallerProtocol.parse(line)), timeoutMs);
        if (rc != 0) throw new IllegalStateException("上游 helper 命令失败，退出码 " + rc + "：" + tail(lines));
        for (String line : lines) {
            InstallerProtocol.Event e = InstallerProtocol.parse(line);
            if ("web-error".equals(e.type)) throw new IllegalStateException("上游 helper 拒绝操作：" + e.value);
        }
        return lines;
    }

    private static String firstPayload(List<String> lines, String type) {
        for (String line : lines) {
            InstallerProtocol.Event e = InstallerProtocol.parse(line);
            if (type.equals(e.type)) return e.value;
        }
        return null;
    }

    private static Map<String, String> parseSums(String text) {
        Map<String, String> map = new HashMap<>();
        for (String line : text.split("\\r?\\n")) {
            String[] p = line.trim().split("\\s+", 2);
            if (p.length == 2 && InstallerProtocol.safeSha256(p[0]) && InstallerProtocol.safeArgument(p[1])) map.put(p[1], p[0]);
        }
        return map;
    }

    private static byte[] readAsset(AssetManager a, String path, long limit) throws Exception {
        try (InputStream in = a.open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > limit) throw new IllegalStateException("APK helper 超过脚本大小限制"); out.write(b, 0, n); }
            return out.toByteArray();
        }
    }
    private static String readAssetText(AssetManager a, String path, long limit) throws Exception {
        return new String(readAsset(a, path, limit), StandardCharsets.UTF_8);
    }
    private static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b); StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format(Locale.ROOT, "%02x", x & 255)); return s.toString();
    }
    private static String shellQuote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private static boolean contains(List<String> l, String s) { for (String x : l) if (x.contains(s)) return true; return false; }
    private static boolean containsPrefix(List<String> l, String s) { for (String x : l) if (x.startsWith(s)) return true; return false; }
    private static String tail(List<String> l) { int from = Math.max(0, l.size() - 3); return l.subList(from, l.size()).toString(); }
}
