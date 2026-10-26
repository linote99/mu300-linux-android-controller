package org.linote.mu300controller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only preflight for the upstream experimental make-room flow. */
final class MakeRoomPreflight {
    static final class Result {
        final String device, serial, slot, cid, node, crypto, cryptoState, cryptoType, dataSource;
        final long userdataStart, userdataSize, diskSectors;
        final GptTable gpt;
        final GptTable.Partition userdata;
        final long maxLinuxSectors;
        Result(String device, String serial, String slot, String cid, String node, String crypto, String cryptoState,
               String cryptoType, String dataSource, long userdataStart, long userdataSize, long diskSectors,
               GptTable gpt, GptTable.Partition userdata) {
            this.device=device; this.serial=serial; this.slot=slot; this.cid=cid; this.node=node; this.crypto=crypto;
            this.cryptoState=cryptoState; this.cryptoType=cryptoType; this.dataSource=dataSource;
            this.userdataStart=userdataStart; this.userdataSize=userdataSize; this.diskSectors=diskSectors;
            this.gpt=gpt; this.userdata=userdata;
            this.maxLinuxSectors=MakeRoomProtocol.maxLinuxSectors(userdata.first, gpt.usableEnd());
        }
        long maxLinuxBytes() { return maxLinuxSectors * 512L; }
        String identitySummary() { return "设备=" + device + "，序列号=" + serial + "，槽位=" + slot + "，CID尾号=" + cid.substring(Math.max(0,cid.length()-8)); }
    }

    private MakeRoomPreflight() { }

    static String[] readSerialCid(AdbConnection c) throws Exception {
        List<String> lines=new ArrayList<>();
        int rc=c.shell("su -c 'echo SERIAL=$(getprop ro.serialno); echo CID=$(cat /sys/class/block/mmcblk0/device/cid)'",lines::add,30000);
        if(rc!=0) throw new IllegalStateException("无法读取目标序列号/eMMC CID");
        String serial="",cid="";
        for(String line:lines) { if(line.startsWith("SERIAL="))serial=line.substring(7).trim(); else if(line.startsWith("CID="))cid=line.substring(4).trim(); }
        if(serial.isEmpty()||!cid.matches("[0-9a-f]{32}")) throw new IllegalStateException("目标序列号或 eMMC CID 无效");
        return new String[]{serial,cid};
    }

    static Result inspect(AdbConnection c, JSONObject status) throws Exception { return inspect(c, status, true); }

    static Result inspectForResume(AdbConnection c, JSONObject status) throws Exception { return inspect(c, status, false); }

    private static Result inspect(AdbConnection c, JSONObject status, boolean preparing) throws Exception {
        String device = status.optString("device", "");
        if (!("f50".equals(device) || "u30air".equals(device))) throw new IllegalStateException("官方 helper 未识别为 F50/MU300 或 U30 Air；禁止 userdata 缩容。");
        if (status.optInt("slots_ok", 0) != 1 || status.optInt("magiskboot", 0) != 1 || !"magisk".equals(status.optString("root_kind", "")))
            throw new IllegalStateException("需要已授权 Magisk root、magiskboot 且 A/B 启动槽状态明确。");
        JSONObject misc = status.optJSONObject("misc");
        if (misc == null || misc.optInt("valid", 0) != 1) throw new IllegalStateException("misc 的 A/B 启动控制块无效；缩容前安全检查失败。");
        if(status.optInt("boot_completed",0)!=1)throw new IllegalStateException("Android 尚未完成启动；等待 sys.boot_completed=1 后重试。");
        String linuxBoot=status.optString("linux_boot","");
        if(!"android-copy".equals(linuxBoot)&&!"none".equals(linuxBoot))
            throw new IllegalStateException("当前 Linux 槽不是 Android-copy/none（状态="+linuxBoot+"）；只有 Android 当前正常运行时才允许准备缩容。");

        List<String> lines = new ArrayList<>();
        String script = "set -e; d=$(readlink -f /dev/block/by-name/userdata); n=${d##*/}; b=${n%p*}; "
                + "echo NODE=$n; echo CID=$(cat /sys/class/block/$b/device/cid); "
                + "echo START=$(cat /sys/class/block/$n/start); echo SIZE=$(cat /sys/class/block/$n/size); "
                + "echo DISK=$(cat /sys/class/block/$b/size); echo SERIAL=$(getprop ro.serialno); "
                + "echo SLOT=$(getprop ro.boot.slot_suffix); echo STATE=$(getprop ro.crypto.state); "
                + "echo TYPE=$(getprop ro.crypto.type); "
                + "echo SOURCE=$(awk '$2==\"/data\" {print $1; exit}' /proc/mounts); "
                + "echo MAGIC=$(dd if=$d bs=1 skip=1024 count=4 2>/dev/null | od -An -tx1 | tr -d ' \\n'); "
                + "echo EXT=$(dd if=$d bs=1 skip=1080 count=2 2>/dev/null | od -An -tx1 | tr -d ' \\n')";
        int rc = c.shell("su -c " + quote(script), lines::add, 30000);
        if (rc != 0) throw new IllegalStateException("读取 userdata/eMMC 身份信息失败；未写入设备。");
        java.util.Map<String,String> v = new java.util.HashMap<>();
        for (String line : lines) { int eq=line.indexOf('='); if (eq>0) v.put(line.substring(0,eq),line.substring(eq+1).trim()); }
        String node=req(v,"NODE"), cid=req(v,"CID"), serial=req(v,"SERIAL"), slot=req(v,"SLOT");
        if (!node.matches("mmcblk[0-9]p[0-9]+") || !node.startsWith("mmcblk0p")) throw new IllegalStateException("userdata 不在可识别的 mmcblk0 eMMC 分区上；拒绝缩容。");
        if (!cid.matches("[0-9a-f]{32}")) throw new IllegalStateException("无法读取有效 128-bit eMMC CID；拒绝缩容。");
        if (serial.isEmpty() || "unknown".equalsIgnoreCase(serial)) throw new IllegalStateException("目标未提供稳定设备序列号；拒绝创建恢复事务。");
        if (!"_a".equals(slot) && !"_b".equals(slot)) throw new IllegalStateException("无法确定 Android 当前启动槽；拒绝缩容。");
        String helperSlot=status.optString("android_slot", "");
        if(!slot.substring(1).equals(helperSlot)) throw new IllegalStateException("官方 helper 与 getprop 报告的 Android 槽位不一致。");
        String state=v.getOrDefault("STATE", ""), type=v.getOrDefault("TYPE", ""), source=v.getOrDefault("SOURCE", ""), magic=v.getOrDefault("MAGIC", "");
        if ("53ef".equals(v.get("EXT"))) magic="53ef";
        String crypto;
        if ("encrypted".equals(state) || "file".equals(type) || "block".equals(type) || source.contains("/dm-") || source.contains("/mapper/")) crypto="yes";
        else if ("unencrypted".equals(state) && ("1020f5f2".equals(magic) || "53ef".equals(magic))) crypto="no";
        else crypto="unknown";
        if ("unknown".equals(crypto)) throw new IllegalStateException("Android 加密状态无法判定；上游实验流程要求 unknown 时停止，未写入设备。");

        long start=number(v,"START"), size=number(v,"SIZE"), disk=number(v,"DISK");
        if (size <= 0 || disk <= 0) throw new IllegalStateException("userdata/eMMC sysfs geometry 无效。");
        long statusDisk=status.optLong("disk", -1);
        if (statusDisk != disk) throw new IllegalStateException("helper 与 sysfs 报告的 eMMC sector 数不一致。");
        byte[] head=readRaw(c,"su -c 'dd if=/dev/block/mmcblk0 bs=512 count=34 2>/dev/null'",34*512);
        byte[] tail=readRaw(c,"su -c 'dd if=/dev/block/mmcblk0 bs=512 skip="+(disk-33)+" count=33 2>/dev/null'",33*512);
        GptTable gpt=new GptTable(head,tail,disk);
        GptTable.Partition userdata=gpt.userdataLast();
        if (userdata.first != start || userdata.last - userdata.first + 1 != size)
            throw new IllegalStateException("运行中内核的 userdata sysfs 几何与 GPT 不一致；请重启 Android 后重试。");
        JSONArray parts=status.optJSONArray("parts"); boolean statusMatch=false;
        if (parts!=null) for(int i=0;i<parts.length();i++) {
            JSONArray p=parts.optJSONArray(i); if(p==null||p.length()<3||!"userdata".equals(p.optString(0))) continue;
            statusMatch=p.optLong(1,-1)==start && p.optLong(2,-1)==size;
        }
        if (!statusMatch) throw new IllegalStateException("官方 helper 的分区列表与 GPT/sysfs userdata 不一致。");
        JSONObject region=status.optJSONObject("region");
        if (region==null) throw new IllegalStateException("官方 helper 未报告 Linux region 几何。");
        java.util.Map<String,String> regionValues=new java.util.HashMap<>();
        for(String key:new String[]{"off","size","existing","dirty","part","part_fs","part_count","foreign_image"}) {
            if(!region.has(key))throw new IllegalStateException("官方 helper region 缺少字段 "+key+"；拒绝把未知状态当作空白。");
            Object value=region.opt(key);regionValues.put(key,value==null||value==JSONObject.NULL?"":String.valueOf(value));
        }
        MakeRoomProtocol.RegionStatus regionState;
        try{regionState=MakeRoomProtocol.validateEmptyRegionStatus(regionValues);}
        catch(IllegalArgumentException e){throw new IllegalStateException("官方 helper 未明确报告一个可用的空 region："+e.getMessage());}
        long free=regionState.size,off=regionState.offset;
        if (preparing) {
            if (free < 0 || free >= 700L*1024*1024) throw new IllegalStateException("设备当前不是上游要求的“内部空闲区不足 700 MiB”情形；不启动 userdata 缩容。");
            long lastEnd=status.optLong("last_end",-1);
            if(lastEnd<=0||off<0||off%512!=0||free%512!=0) throw new IllegalStateException("helper 提供的空闲区几何无效；拒绝缩容。");
            if(lastEnd!=Math.addExact(userdata.last,1L))throw new IllegalStateException("helper last_end 与 GPT 最后一分区不一致；拒绝缩容。");
            long startSector=Math.multiplyExact(Math.addExact(lastEnd/MakeRoomProtocol.ALIGN,1L),MakeRoomProtocol.ALIGN);
            long endSector=Math.multiplyExact(Math.subtractExact((disk-34)/MakeRoomProtocol.ALIGN,1L),MakeRoomProtocol.ALIGN);
            if(endSector<=startSector)endSector=startSector;
            long expectedOff=Math.multiplyExact(startSector,512L), expectedSize=Math.multiplyExact(endSector-startSector,512L);
            if(off!=expectedOff||free!=expectedSize)throw new IllegalStateException("官方 helper 与 GPT/sysfs 计算出的空闲区边界不一致。");
            if(free>0){
                gpt.verifyFreeRegion(off/512L,Math.addExact(off/512L,free/512L));
                try{MakeRoomProtocol.requireCleanPreparationSample(regionState.dirty,free);}
                catch(IllegalArgumentException e){throw new IllegalStateException("上游 helper 的空闲区采样不干净/不完整；拒绝覆盖："+e.getMessage());}
                int dirty=sampleRegion(c,off,free);
                if(dirty!=0)throw new IllegalStateException("独立 16 点空闲区采样发现了数据（"+dirty+"/16）；不会覆盖。");
            }
        } else if (free <= 0) throw new IllegalStateException("恢复后的 Linux region 大小无效；拒绝继续。");
        return new Result(device,serial,slot.substring(1),cid,node,crypto,state,type,source,start,size,disk,gpt,userdata);
    }

    static byte[] readRaw(AdbConnection c, String command, int expected) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(expected);
        long n=c.execRaw(command,out,30000);
        if(n!=expected || out.size()!=expected) throw new IllegalStateException("设备只返回了 "+n+" 字节，预期 "+expected+"；二进制读回不完整。");
        return out.toByteArray();
    }

    static byte[] readRawVariable(AdbConnection c,String command,long max) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        long n=c.execRaw(command,out,120000);
        if(n<=0||n>max||out.size()!=n) throw new IllegalStateException("块设备二进制读取长度异常："+n);
        return out.toByteArray();
    }

    private static int sampleRegion(AdbConnection c,long off,long size) throws Exception {
        if(off<0||size<=0||off%512!=0||size%512!=0)throw new IllegalStateException("空闲区采样边界不是完整 sector");
        long total=size/512L, sample=Math.min(2048L,total), span=total-sample, base=off/512L;
        StringBuilder script=new StringBuilder("n=0; ");
        for(int i=0;i<16;i++){
            long skip=Math.addExact(base,Math.multiplyExact(span,(long)i)/15L);
            script.append("c=$(dd if=/dev/block/mmcblk0 bs=512 skip=").append(skip).append(" count=").append(sample)
                    .append(" 2>/dev/null | tr -d '\\000\\377' | wc -c); [ \"$c\" -gt 0 ] && n=$((n+1)); ");
        }
        script.append("echo DIRTY=$n");
        List<String> lines=new ArrayList<>();int rc=c.shell("su -c "+quote(script.toString()),lines::add,180000);
        if(rc!=0)throw new IllegalStateException("独立空闲区 raw sample 失败；拒绝继续。");
        for(String line:lines)if(line.startsWith("DIRTY="))try{int n=Integer.parseInt(line.substring(6).trim());if(n>=0&&n<=16)return n;}catch(NumberFormatException ignored){}
        throw new IllegalStateException("独立空闲区采样未返回有效计数；拒绝继续。");
    }

    static String quote(String s) { return "'"+s.replace("'","'\\''")+"'"; }
    private static String req(java.util.Map<String,String> m,String k) { String s=m.get(k); if(s==null||s.isEmpty()) throw new IllegalStateException("缺少设备信息 "+k); return s; }
    private static long number(java.util.Map<String,String> m,String k) { try { return Long.parseLong(req(m,k)); } catch(NumberFormatException e){throw new IllegalStateException("设备几何字段无效："+k);} }
}
