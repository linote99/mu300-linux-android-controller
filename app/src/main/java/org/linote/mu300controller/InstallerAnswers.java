package org.linote.mu300controller;

/** Exact settings-file contract consumed by mu300-install.sh inside the upstream Magisk ZIP. */
final class InstallerAnswers {
    static final long MIB = 1024L * 1024L;
    static final long NEED_OPENWRT = 800L * MIB;
    static final long NEED_UBUNTU = 1600L * MIB;

    private InstallerAnswers() { }

    static String forSingleSystem(boolean linuxDefault, int attempts, boolean hotspot, boolean gpu) {
        return forSingleSystem(linuxDefault, attempts, hotspot, gpu, false);
    }

    static String forSingleSystem(boolean linuxDefault, int attempts, boolean hotspot, boolean gpu, boolean regionOverwrite) {
        if (attempts < 1 || attempts > 6) throw new IllegalArgumentException("boot attempts must be 1..6");
        StringBuilder b = new StringBuilder();
        line(b, "MU300_LANG", "zh");
        line(b, "MU300_STORAGE", "internal");
        line(b, "MU300_MODE", "update");
        line(b, "MU300_BOOT", linuxDefault ? "linux" : "android");
        line(b, "MU300_BOOT_ATTEMPTS", String.valueOf(attempts));
        line(b, "MU300_HOTSPOT", hotspot ? "yes" : "no");
        line(b, "MU300_GPU", gpu ? "yes" : "no");
        line(b, "MU300_SYNC_BOOTCHAIN", "1");
        if (regionOverwrite) line(b, "MU300_REGION_OVERWRITE", "yes");
        return b.toString();
    }

    static String storageBlocker(String device, long regionBytes, String existing, int dirty,
                                 int partCount, String partFs, boolean foreignImage, String system) {
        if (!("f50".equals(device) || "u30air".equals(device))) return "unknown-device";
        if (partCount < 0 || partCount > 1) return "ambiguous-linux-partition";
        if (foreignImage) return "foreign-linux-image";
        if ("ext4".equals(partFs) && !"yes".equals(existing)) return "foreign-linux-filesystem";
        if ("yes".equals(existing)) return ""; // upstream installer validates the existing filesystem again
        if (dirty != 0) return dirty < 0 ? "storage-sample-unknown" : "region-not-empty";
        long minimum = system != null && system.startsWith("ubuntu") ? NEED_UBUNTU : NEED_OPENWRT;
        if (regionBytes < minimum) {
            // The phone/web helper deliberately does not repartition. This includes the experimental 32-GB path.
            return regionBytes <= 0 ? "make-room-unsupported" : "region-too-small";
        }
        return "";
    }

    private static void line(StringBuilder b, String key, String value) {
        if (b.length() > 0) b.append('\n');
        b.append(key).append('=').append(value);
    }
}
