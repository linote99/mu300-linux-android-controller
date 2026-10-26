package org.linote.mu300controller;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;

/** Pure parser for the upstream installer/helper marker lines; safe to exercise without Android or a device. */
final class InstallerProtocol {
    private static final Pattern STEP = Pattern.compile("^MU300-STEP ([0-9]{1,2})/([0-9]{1,2}) ([a-z-]+)$");
    private static final Pattern PLAN = Pattern.compile("^MU300-PLAN ([a-z_]+)=(.*)$");
    private static final Pattern EXIT = Pattern.compile("^MU300-WEB-EXIT ([0-9]{1,3}|interrupted)$");
    private static final Pattern PROGRESS = Pattern.compile("^MU300-PROGRESS ([0-9]{1,9}) ([0-9]{1,9})$");
    private static final Pattern JSON_EVENT = Pattern.compile("^@@mu300\\s+(\\{.*\\})$");
    private static final Pattern EVENT_NAME = Pattern.compile("\"event\"\\s*:\\s*\"([a-z_]+)\"");
    private static final String[] PLAN_FIELDS = {"tag","system","os","ubuntu","kernel","device","storage","sd_dev","offset","size",
            "format","update","have","boot_os","default_linux","boot_attempts","hotspot","gpu","password","password_checked",
            "extras","adb","linux_slot","android_slot","sync_bootchain","bootchain","dry_run"};

    static final class Event {
        final String type;
        final String key;
        final String value;
        final String raw;
        final int n;
        final int of;
        final int rc;
        Event(String type, String key, String value, String raw, int n, int of, int rc) {
            this.type = type; this.key = key; this.value = value; this.raw = raw; this.n = n; this.of = of; this.rc = rc;
        }
    }

    private InstallerProtocol() { }

    static boolean safeArgument(String s) {
        return s != null && s.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") && !s.contains("..");
    }

    static boolean safeZipName(String s) {
        return s != null && s.matches("mu300-magisk-v[0-9][A-Za-z0-9._-]{0,80}\\.zip") && !s.contains("..");
    }

    static boolean safeSha256(String s) { return s != null && s.matches("[0-9a-f]{64}"); }

    static boolean completePlan(Map<String,String> values) {
        if(values==null)return false;
        for(String key:PLAN_FIELDS)if(!values.containsKey(key)||values.get(key)==null)return false;
        return true;
    }

    static boolean answersMatch(Map<String,String> values,int attempts,boolean linuxDefault,boolean hotspot,boolean gpu) {
        return values!=null&&Integer.toString(attempts).equals(values.get("boot_attempts"))
                &&(linuxDefault?"1":"0").equals(values.get("default_linux"))
                &&(hotspot?"1":"0").equals(values.get("hotspot"))
                &&(gpu?"yes":"no").equals(values.get("gpu"));
    }

    static Event parse(String line) {
        if (line == null) return event("malformed", "", "null line", "", 0, 0, -1);
        Matcher m = STEP.matcher(line);
        if (m.matches()) return event("step", m.group(3), "", line, integer(m.group(1)), integer(m.group(2)), -1);
        m = PLAN.matcher(line);
        if (m.matches()) return event("plan", m.group(1), m.group(2), line, 0, 0, -1);
        m = EXIT.matcher(line);
        if (m.matches()) return event("exit", "", m.group(1), line, 0, 0,
                "interrupted".equals(m.group(1)) ? -2 : integer(m.group(1)));
        m = PROGRESS.matcher(line);
        if (m.matches()) return event("progress", "", "", line, integer(m.group(1)), integer(m.group(2)), -1);
        if (line.startsWith("MU300-WEB-STATUS ")) return event("status", "", line.substring(17), line, 0, 0, -1);
        if (line.startsWith("MU300-WEB-ZIP ")) return event("zip", "", line.substring(14), line, 0, 0, -1);
        if (line.startsWith("MU300-WEB-ERROR ")) return event("web-error", "", line.substring(16), line, 0, 0, -1);
        if (line.startsWith("MU300-WEB-JOB ")) return event("job", "", line.substring(14), line, 0, 0, -1);
        if (line.startsWith("MU300-WEB-ANSWERS ")) return event("answers", "", line.substring(18), line, 0, 0, -1);
        if (line.startsWith("MU300-WEB-PASSWORD ")) return event("password", "", line.substring(19), line, 0, 0, -1);
        m = JSON_EVENT.matcher(line);
        if (m.matches()) {
            String json = m.group(1);
            if (!balancedObject(json)) return event("malformed", "", "invalid JSON event", line, 0, 0, -1);
            Matcher em = EVENT_NAME.matcher(json);
            if (!em.find()) return event("malformed", "", "missing event name", line, 0, 0, -1);
            return event("json-" + em.group(1), "", json, line, 0, 0, -1);
        }
        if (line.startsWith("! ")) return event("error", "", line.substring(2), line, 0, 0, -1);
        return event("text", "", line, line, 0, 0, -1);
    }

    private static Event event(String type, String key, String value, String raw, int n, int of, int rc) {
        return new Event(type, key, value, raw, n, of, rc);
    }

    private static int integer(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    private static boolean balancedObject(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != '{' || s.charAt(s.length() - 1) != '}') return false;
        boolean quoted = false, escape = false;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') quoted = false;
            } else {
                if (c == '"') quoted = true;
                else if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth < 0) return false; }
            }
        }
        return !quoted && !escape && depth == 0;
    }
}
