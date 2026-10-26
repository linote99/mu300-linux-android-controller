package org.linote.mu300controller;

/** Parses the numeric version code printed by `magisk -V`. */
final class MagiskVersion {
    private MagiskVersion() { }

    static int parseVersionCode(Iterable<String> lines) {
        if (lines == null) return -1;
        for (String line : lines) {
            if (line == null) continue;
            String value = line.trim();
            if (!value.matches("[0-9]{4,6}")) continue;
            try { return Integer.parseInt(value); }
            catch (NumberFormatException ignored) { return -1; }
        }
        return -1;
    }

    static boolean supportsInstaller(int versionCode) {
        return versionCode >= 26000;
    }
}
