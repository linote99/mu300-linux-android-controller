package org.linote.mu300controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Safe inventory and narrowly scoped cleanup for this app's unprivileged target staging directory. */
final class TargetStageCleanup {
    static final String STAGE = "/data/local/tmp/mu300-web";
    private static final String[] HELPERS = {
            "android-web.sh", "storage.sh", "android-boot-image.sh",
            "android-mount-mu300root.sh", "switch.sh"
    };

    static final class RemoteFile {
        final String relativePath;
        final long bytes;
        RemoteFile(String relativePath, long bytes) { this.relativePath = relativePath; this.bytes = bytes; }
        @Override public String toString() { return relativePath + " · " + human(bytes); }
    }

    static final class Inventory {
        final boolean rootExists;
        final List<RemoteFile> files;
        final List<String> unsafe;
        final boolean unknownEntries;
        Inventory(boolean rootExists, List<RemoteFile> files, List<String> unsafe, boolean unknownEntries) {
            this.rootExists = rootExists;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.unsafe = Collections.unmodifiableList(new ArrayList<>(unsafe));
            this.unknownEntries = unknownEntries;
        }
        long bytes() { long n = 0; for (RemoteFile f : files) n = Math.addExact(n, f.bytes); return n; }
        boolean canClean() { return rootExists && !files.isEmpty() && unsafe.isEmpty() && !unknownEntries; }
    }

    private TargetStageCleanup() { }

    static String inspectCommand() {
        StringBuilder s = new StringBuilder();
        s.append("set -eu; d='").append(STAGE).append("'; ");
        s.append("[ ! -L \"$d\" ] || { echo 'MU300-OTG-CLEAN|UNSAFE|stage'; exit 12; }; ");
        s.append("[ -d \"$d\" ] || { echo 'MU300-OTG-CLEAN|ROOT|MISSING'; exit 0; }; ");
        s.append("echo 'MU300-OTG-CLEAN|ROOT|OK'; ");
        for (String name : HELPERS) appendCheckForReport(s, name, name);
        s.append("i=\"$d/in\"; if [ -L \"$i\" ]; then echo 'MU300-OTG-CLEAN|UNSAFE|in'; ");
        s.append("elif [ -d \"$i\" ]; then ");
        s.append("for f in \"$i\"/*; do [ -e \"$f\" ] || [ -L \"$f\" ] || continue; b=${f##*/}; ");
        s.append("case \"$b\" in answers.conf|mu300-magisk-v*.zip) ;; *) echo 'MU300-OTG-CLEAN|UNKNOWN|in'; continue ;; esac; ");
        s.append("if [ -L \"$f\" ]; then echo \"MU300-OTG-CLEAN|UNSAFE|in/$b\"; ");
        s.append("elif [ -f \"$f\" ]; then n=$(wc -c < \"$f\" | tr -d ' '); echo \"MU300-OTG-CLEAN|FILE|in/$b|$n\"; ");
        s.append("else echo 'MU300-OTG-CLEAN|UNKNOWN|in'; fi; done; ");
        s.append("elif [ -e \"$i\" ]; then echo 'MU300-OTG-CLEAN|UNSAFE|in'; fi; ");
        appendUnknownProbe(s, "\"$d\"", "android-web.sh storage.sh android-boot-image.sh android-mount-mu300root.sh switch.sh in");
        s.append("for f in \"$d\"/.[!.]* \"$d\"/..?*; do [ -e \"$f\" ] || [ -L \"$f\" ] || continue; echo 'MU300-OTG-CLEAN|UNKNOWN|stage'; done; ");
        s.append("if [ -d \"$i\" ] && [ ! -L \"$i\" ]; then for f in \"$i\"/.[!.]* \"$i\"/..?*; do [ -e \"$f\" ] || [ -L \"$f\" ] || continue; echo 'MU300-OTG-CLEAN|UNKNOWN|in'; done; fi");
        return s.toString();
    }

    private static void appendCheckForReport(StringBuilder s, String name, String relative) {
        s.append("f=\"$d/").append(name).append("\"; ");
        s.append("if [ -L \"$f\" ]; then echo 'MU300-OTG-CLEAN|UNSAFE|").append(relative).append("'; ");
        s.append("elif [ -f \"$f\" ]; then n=$(wc -c < \"$f\" | tr -d ' '); echo \"MU300-OTG-CLEAN|FILE|")
                .append(relative).append("|$n\"; elif [ -e \"$f\" ]; then echo 'MU300-OTG-CLEAN|UNKNOWN|stage'; fi; ");
    }

    private static void appendUnknownProbe(StringBuilder s, String dir, String knownNames) {
        s.append("for f in ").append(dir).append("/*; do [ -e \"$f\" ] || [ -L \"$f\" ] || continue; b=${f##*/}; ");
        s.append("case \" $knownNames \" in *\" $b \"*) ;; *) echo 'MU300-OTG-CLEAN|UNKNOWN|stage' ;; esac; done; ");
    }

    static Inventory parse(List<String> lines) {
        boolean root = false, markerSeen = false, unknown = false;
        List<RemoteFile> files = new ArrayList<>();
        List<String> unsafe = new ArrayList<>();
        for (String raw : lines) {
            if (raw == null || !raw.startsWith("MU300-OTG-CLEAN|")) continue;
            String[] p = raw.trim().split("\\|", -1);
            if (p.length < 3 || !"MU300-OTG-CLEAN".equals(p[0])) continue;
            if ("ROOT".equals(p[1]) && p.length == 3) {
                if ("OK".equals(p[2])) { root = true; markerSeen = true; }
                else if ("MISSING".equals(p[2])) { root = false; markerSeen = true; }
            } else if ("FILE".equals(p[1]) && p.length == 4) {
                long size;
                try { size = Long.parseLong(p[3]); } catch (NumberFormatException e) { continue; }
                if (size < 0 || !safeRelativePath(p[2])) continue;
                boolean duplicate = false;
                for (RemoteFile f : files) if (f.relativePath.equals(p[2])) duplicate = true;
                if (!duplicate) files.add(new RemoteFile(p[2], size));
            } else if ("UNSAFE".equals(p[1]) && p.length == 3) {
                unsafe.add(p[2]);
            } else if ("UNKNOWN".equals(p[1])) {
                unknown = true;
            }
        }
        if (!markerSeen) throw new IllegalStateException("目标暂存目录扫描没有返回完整标记；未清理任何文件。");
        if (!root && !files.isEmpty()) throw new IllegalStateException("目标暂存目录状态不一致；未清理任何文件。");
        return new Inventory(root, files, unsafe, unknown);
    }

    private static boolean safeRelativePath(String path) {
        if (path == null || path.length() > 180 || path.contains("..") || path.contains("|") || path.contains("/") && !path.startsWith("in/")) return false;
        if (path.startsWith("in/")) {
            String name = path.substring(3);
            return "answers.conf".equals(name) || InstallerProtocol.safeZipName(name);
        }
        for (String name : HELPERS) if (name.equals(path)) return true;
        return false;
    }

    static String deleteCommand(Inventory inventory) {
        if (inventory == null || !inventory.canClean()) throw new IllegalArgumentException("目标暂存清理未通过安全检查");
        StringBuilder s = new StringBuilder("set -eu; d='").append(STAGE).append("'; ");
        s.append("[ ! -L \"$d\" ] && [ -d \"$d\" ] || { echo 'MU300-OTG-CLEAN|REFUSED'; exit 12; }; ");
        s.append("j=$(su -c 'sh ").append(STAGE).append("/android-web.sh status' 2>/dev/null); ");
        s.append("case \"$j\" in *'\"state\":\"running\"'*) echo 'MU300-OTG-CLEAN|JOB-RUNNING'; exit 14 ;; esac; ");
        boolean hasInbox = false;
        for (RemoteFile f : inventory.files) {
            if (f.relativePath.startsWith("in/")) {
                hasInbox = true;
                s.append("[ ! -L \"$d/in\" ] && [ -d \"$d/in\" ] || { echo 'MU300-OTG-CLEAN|REFUSED'; exit 12; }; ");
            }
            String q = "$d/" + f.relativePath;
            s.append("f=\"").append(q).append("\"; [ ! -L \"$f\" ] && [ -f \"$f\" ] || { echo 'MU300-OTG-CLEAN|CHANGED'; exit 13; }; ");
            s.append("n=$(wc -c < \"$f\" | tr -d ' '); [ \"$n\" = '").append(f.bytes).append("' ] || { echo 'MU300-OTG-CLEAN|CHANGED'; exit 13; }; ");
        }
        for (RemoteFile f : inventory.files) s.append("rm -f \"$d/").append(f.relativePath).append("\"; ");
        if (hasInbox) s.append("rmdir \"$d/in\" 2>/dev/null || true; ");
        s.append("rmdir \"$d\" 2>/dev/null || true; echo 'MU300-OTG-CLEAN|DONE'");
        return s.toString();
    }

    static String human(long b) {
        if (b < (1L << 20)) return String.format(Locale.US, "%.1f KiB", b / 1024.0);
        if (b < (1L << 30)) return String.format(Locale.US, "%.1f MiB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GiB", b / 1073741824.0);
    }
}
