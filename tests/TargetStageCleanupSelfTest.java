package org.linote.mu300controller;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;

public final class TargetStageCleanupSelfTest {
    private static void ok(boolean b, String message) { if (!b) throw new AssertionError(message); }
    private static void throwsIt(Runnable r, String message) {
        try { r.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
    private static void shellSyntax(String script) throws Exception {
        Process p = new ProcessBuilder("sh", "-n").start();
        try (OutputStream out = p.getOutputStream()) { out.write(script.getBytes("UTF-8")); }
        if (p.waitFor() != 0) throw new AssertionError("generated remote shell command is invalid");
    }

    public static void main(String[] args) throws Exception {
        ok(RemoteJobState.blocksTransfer("install", "running", -1), "active helper job blocks transfer");
        ok(!RemoteJobState.blocksTransfer("plan", "done", 3), "completed dry-run rc=3 does not block transfer");
        ok(!RemoteJobState.blocksTransfer("plan", "interrupted", -2), "interrupted terminal job is not active");
        ok(!RemoteJobState.blocksTransfer("plan", "none", -1), "no helper job does not block transfer");

        TargetStageCleanup.Inventory inv = TargetStageCleanup.parse(Arrays.asList(
                "noise from shell",
                "MU300-OTG-CLEAN|ROOT|OK",
                "MU300-OTG-CLEAN|FILE|android-web.sh|4096",
                "MU300-OTG-CLEAN|FILE|in/answers.conf|128",
                "MU300-OTG-CLEAN|FILE|in/mu300-magisk-v2026.10.26-openwrt-k7.2.zip|2048"));
        ok(inv.rootExists && inv.files.size() == 3 && inv.bytes() == 6272, "scan lists known helper and pending input files with sizes");
        ok(inv.canClean(), "all-known regular staging files are cleanable");
        String delete = TargetStageCleanup.deleteCommand(inv);
        shellSyntax(TargetStageCleanup.inspectCommand());
        shellSyntax(delete);
        ok(delete.contains("/data/local/tmp/mu300-web") && delete.contains("'4096'") && delete.contains("'128'"), "delete command is bound to scanned stage paths and sizes");
        ok(!delete.contains("/data/adb") && !delete.contains("rm -rf") && delete.contains("MU300-OTG-CLEAN|DONE"), "clean never touches root task/resume data or recursively deletes");
        ok(delete.contains("android-web.sh status") && delete.contains("JOB-RUNNING"), "delete script rechecks helper job immediately before removal");
        ok(TargetStageCleanup.inspectCommand().contains("[ ! -L \"$d\" ]")
                && TargetStageCleanup.inspectCommand().contains("if [ -L \"$f\" ]")
                && delete.contains("[ ! -L \"$f\" ]"), "scanner reports links and delete refuses to follow them");

        TargetStageCleanup.Inventory unknown = TargetStageCleanup.parse(Arrays.asList(
                "MU300-OTG-CLEAN|ROOT|OK", "MU300-OTG-CLEAN|FILE|android-web.sh|1", "MU300-OTG-CLEAN|UNKNOWN|in"));
        ok(!unknown.canClean(), "unknown target directory entry blocks cleanup");
        TargetStageCleanup.Inventory link = TargetStageCleanup.parse(Arrays.asList(
                "MU300-OTG-CLEAN|ROOT|OK", "MU300-OTG-CLEAN|FILE|android-web.sh|1", "MU300-OTG-CLEAN|UNSAFE|in/answers.conf"));
        ok(!link.canClean(), "symlink/untrusted target entry blocks cleanup");
        TargetStageCleanup.Inventory missing = TargetStageCleanup.parse(Collections.singletonList("MU300-OTG-CLEAN|ROOT|MISSING"));
        ok(!missing.rootExists && !missing.canClean(), "missing staging directory is an empty safe result");
        throwsIt(() -> TargetStageCleanup.deleteCommand(unknown), "cannot generate delete command for unknown entries");
        throwsIt(() -> TargetStageCleanup.parse(Collections.singletonList("MU300-OTG-CLEAN|FILE|../../x|1")), "missing scan marker rejected");
        System.out.println("TargetStageCleanupSelfTest: all checks passed (no USB or device access).");
    }
}
