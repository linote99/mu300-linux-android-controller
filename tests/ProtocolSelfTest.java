package org.linote.mu300controller;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class ProtocolSelfTest {
    private static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        ok(MagiskVersion.parseVersionCode(Arrays.asList("1e3edb88:MAGISK:R", "28103"))==28103,"debug Magisk name uses numeric -V version code");
        ok(MagiskVersion.supportsInstaller(28103),"version code 28103 satisfies Magisk 26+ requirement");
        ok(!MagiskVersion.supportsInstaller(25999),"version code below 26000 is rejected");
        ok(MagiskVersion.parseVersionCode(Arrays.asList("1e3edb88:MAGISK:R"))==-1,"debug commit name is not parsed as a numeric version");
        InstallerProtocol.Event e=InstallerProtocol.parse("MU300-PLAN dry_run=1");
        ok("plan".equals(e.type)&&"dry_run".equals(e.key)&&"1".equals(e.value),"dry-run plan marker");
        e=InstallerProtocol.parse("MU300-PLAN package=a=b");ok("a=b".equals(e.value),"plan values preserve extra equals");
        e=InstallerProtocol.parse("MU300-STEP 6/14 install");ok("step".equals(e.type)&&e.n==6&&e.of==14&&"install".equals(e.key),"structured step event");
        e=InstallerProtocol.parse("MU300-PROGRESS 75 100");ok("progress".equals(e.type)&&e.n==75&&e.of==100,"structured progress event");
        e=InstallerProtocol.parse("@@mu300 {\"event\":\"status\",\"region\":{\"size\":1024}}");
        ok("json-status".equals(e.type)&&e.value.contains("\"size\":1024"),"JSON event marker with nested object parses");
        e=InstallerProtocol.parse("MU300-WEB-EXIT 3");ok("exit".equals(e.type)&&e.rc==3,"official dry-run terminal rc");
        e=InstallerProtocol.parse("MU300-WEB-EXIT interrupted");ok(e.rc==-2,"interrupted job terminal");
        Map<String,String> plan=new HashMap<>();
        String[] fields={"tag","system","os","ubuntu","kernel","device","storage","sd_dev","offset","size","format","update","have",
                "boot_os","default_linux","boot_attempts","hotspot","gpu","password","password_checked","extras","adb","linux_slot","android_slot",
                "sync_bootchain","bootchain","dry_run"};
        for(String key:fields)plan.put(key,"");
        plan.put("dry_run","1");plan.put("boot_attempts","5");plan.put("default_linux","1");plan.put("hotspot","1");plan.put("gpu","yes");
        ok(InstallerProtocol.completePlan(plan),"all upstream machine plan fields required, empty optional values allowed");
        ok(InstallerProtocol.answersMatch(plan,5,true,true,true),"answers match upstream 1/0 and yes/no value encodings");
        plan.put("gpu","1");ok(!InstallerProtocol.answersMatch(plan,5,true,true,true),"numeric GPU value is not mistaken for upstream yes");
        plan.remove("bootchain");ok(!InstallerProtocol.completePlan(plan),"missing machine plan field rejected");
        ok(InstallerProtocol.safeZipName("mu300-magisk-v2026.10.26.1-openwrt-k5.4.zip"),"official ZIP name accepted");
        ok(!InstallerProtocol.safeZipName("../../answers.zip")&&!InstallerProtocol.safeArgument("bad/name"),"path-like helper args rejected");
        String answers=InstallerAnswers.forSingleSystem(true,5,true,true,false);
        ok(answers.contains("MU300_MODE=update")&&answers.contains("MU300_BOOT=linux")&&answers.contains("MU300_BOOT_ATTEMPTS=5"),"single-system answers");
        ok(!answers.contains("MU300_REGION_OVERWRITE"),"normal install never sets overwrite authorization");
        String resume=InstallerAnswers.forSingleSystem(false,2,false,false,true);
        ok(resume.contains("MU300_REGION_OVERWRITE=yes")&&resume.contains("MU300_BOOT=android")&&resume.contains("MU300_GPU=no"),"resume-only trusted overwrite answer");
        ok("make-room-unsupported".equals(InstallerAnswers.storageBlocker("u30air",0,"no",0,0,"none",false,"openwrt")),"no-room gate");
        ok("region-not-empty".equals(InstallerAnswers.storageBlocker("u30air",1024,"no",1,0,"none",false,"openwrt")),"dirty region gate");
        ok("foreign-linux-image".equals(InstallerAnswers.storageBlocker("f50",1024,"no",0,0,"none",true,"openwrt")),"foreign image gate");
        System.out.println("ProtocolSelfTest: all checks passed (no USB or device access).");
    }
}
