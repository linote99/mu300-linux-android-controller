package org.linote.mu300controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/** Pure transaction/math port of tools/resume.py and the experimental make-room arithmetic in install.sh. */
final class MakeRoomProtocol {
    static final long SECTOR=512L, ALIGN=4096L;
    static final long MIN_ANDROID_SECTORS=(4L*1024*1024*1024)/SECTOR;
    static final long MIN_OPENWRT_SECTORS=(800L*1024*1024)/SECTOR;
    static final long MIN_UBUNTU_SECTORS=(1600L*1024*1024)/SECTOR;
    static final long MIN_BOTH_SECTORS=(2400L*1024*1024)/SECTOR;
    private static final byte[] MARKER_PREFIX="MU300-PREPARED-V1 ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BCB_COMMAND="boot-recovery".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BCB_RECOVERY="recovery\n--wipe_data\n--reason=mu300-make-room\n".getBytes(StandardCharsets.US_ASCII);

    static final class Split {
        final long requestedSectors,newUserdataEnd,userdataSectors,regionStart,regionEnd,regionSectors;
        final long userdataBytes,regionBytes;
        Split(long req,long end,long ud,long rs,long re) {
            requestedSectors=req;newUserdataEnd=end;userdataSectors=ud;regionStart=rs;regionEnd=re;
            regionSectors=Math.max(0,re-rs);userdataBytes=Math.multiplyExact(ud,SECTOR);regionBytes=Math.multiplyExact(regionSectors,SECTOR);
        }
    }
    private MakeRoomProtocol() { }

    static long minimumFor(String os) { return os!=null&&os.startsWith("ubuntu")?MIN_UBUNTU_SECTORS:MIN_OPENWRT_SECTORS; }
    static final class RegionStatus {
        final long offset,size;
        final int dirty;
        RegionStatus(long offset,long size,int dirty){this.offset=offset;this.size=size;this.dirty=dirty;}
    }
    static RegionStatus validateEmptyRegionStatus(Map<String,String> values) {
        for(String k:new String[]{"off","size","existing","dirty","part","part_fs","part_count","foreign_image"})
            if(values==null||!values.containsKey(k)||values.get(k)==null||values.get(k).isEmpty())throw new IllegalArgumentException("helper region status 缺少 "+k);
        if(!"no".equals(values.get("existing")))throw new IllegalArgumentException("helper region.existing 必须明确为 no");
        if(!"none".equals(values.get("part_fs")))throw new IllegalArgumentException("helper region.part_fs 必须明确为 none");
        long off=parseRegionLong(values,"off"),size=parseRegionLong(values,"size");
        int dirty=parseRegionInt(values,"dirty"),part=parseRegionInt(values,"part"),count=parseRegionInt(values,"part_count"),foreign=parseRegionInt(values,"foreign_image");
        if(dirty < -1 || dirty > 16 || part!=0 || count!=0 || foreign!=0)
            throw new IllegalArgumentException("helper region dirty/partition/image 状态不是受支持的空 region");
        return new RegionStatus(off,size,dirty);
    }
    static void requireCleanPreparationSample(int dirty,long regionBytes) {
        if(regionBytes<0)throw new IllegalArgumentException("空闲区大小为负数");
        if(dirty>0)throw new IllegalArgumentException("空闲区采样发现非擦除数据");
        if(dirty<0&&regionBytes>=16L*1024*1024)throw new IllegalArgumentException("大于等于 16 MiB 的空闲区必须有有效 helper 采样");
    }
    private static long parseRegionLong(Map<String,String> values,String key) {
        String s=values.get(key);if(!s.matches("[0-9]{1,19}"))throw new IllegalArgumentException("helper region 几何无效："+key);
        try{return Long.parseLong(s);}catch(NumberFormatException e){throw new IllegalArgumentException("helper region 几何溢出："+key);}
    }
    private static int parseRegionInt(Map<String,String> values,String key) {
        String s=values.get(key);if(!s.matches("-?[0-9]{1,3}"))throw new IllegalArgumentException("helper region 状态无效："+key);
        try{return Integer.parseInt(s);}catch(NumberFormatException e){throw new IllegalArgumentException("helper region 状态溢出："+key);}
    }
    static long maxLinuxSectors(long userdataFirst,long usableEnd) {
        if(userdataFirst<0||usableEnd<userdataFirst) return 0;
        long total=Math.addExact(Math.subtractExact(usableEnd,userdataFirst),1L);
        return Math.max(0,total-MIN_ANDROID_SECTORS);
    }

    /** Mirrors install.sh: decimal GiB -> sectors, rounded down to 4096-sector alignment. */
    static Split split(String gibText,long diskSectors,long userdataFirst,long usableEnd,String os) {
        if(diskSectors<68||diskSectors>Long.MAX_VALUE/SECTOR||userdataFirst<34||usableEnd<userdataFirst||usableEnd>diskSectors-34)
            throw new IllegalArgumentException("GPT/eMMC sector geometry is invalid");
        if(gibText==null||gibText.trim().length()>32||!gibText.trim().matches("(?:[0-9]+(?:\\.[0-9]{1,4})?|\\.[0-9]{1,4})"))
            throw new IllegalArgumentException("Linux 大小必须为十进制 GiB 数字，最多保留 4 位小数");
        BigDecimal gib;
        try { gib=new BigDecimal(gibText.trim()); } catch(RuntimeException e) { throw new IllegalArgumentException("请输入有效的 Linux GiB 数值"); }
        if(gib.signum()<=0||gib.scale()>4) throw new IllegalArgumentException("Linux 大小必须为正数，最多保留 4 位小数");
        BigDecimal sectorsDecimal=gib.multiply(BigDecimal.valueOf(1024L*1024*1024)).divide(BigDecimal.valueOf(SECTOR),0,RoundingMode.DOWN);
        long sectors;
        try { sectors=sectorsDecimal.longValueExact(); } catch(ArithmeticException e) { throw new IllegalArgumentException("Linux 大小超出范围"); }
        sectors=(sectors/ALIGN)*ALIGN;
        if(sectors<=0) throw new IllegalArgumentException("对齐后分区大小为 0");
        if(sectors>maxLinuxSectors(userdataFirst,usableEnd)) throw new IllegalArgumentException("该大小会让 Android userdata 小于上游最低保留的 4 GiB");
        long diskAlignedEnd=Math.multiplyExact(Math.subtractExact((diskSectors-34)/ALIGN,1L),ALIGN);
        long newEnd=Math.subtractExact(Math.subtractExact(Math.subtractExact(diskAlignedEnd,sectors),ALIGN),1L);
        if(newEnd>usableEnd) newEnd=Math.subtractExact(usableEnd,sectors);
        if(newEnd<=userdataFirst) throw new IllegalArgumentException("userdata 将变为空分区");
        long regionStart=Math.multiplyExact(Math.addExact(Math.addExact(newEnd,1L)/ALIGN,1L),ALIGN);
        long regionEnd=diskAlignedEnd;
        if(regionEnd<=regionStart) regionEnd=regionStart;
        long userdataSectors=Math.addExact(Math.subtractExact(newEnd,userdataFirst),1L);
        if(userdataSectors<MIN_ANDROID_SECTORS) throw new IllegalArgumentException("Android userdata 将少于 4 GiB");
        if(regionStart<=newEnd||regionEnd>diskSectors-34||regionEnd>usableEnd+1)
            throw new IllegalArgumentException("缩容计算出的 Linux region 越过 userdata/GPT 安全边界");
        long actual=regionEnd-regionStart;
        if(actual<minimumFor(os)) throw new IllegalArgumentException("依照上游 2 MiB 对齐规则，实际 Linux 空间不足所选系统最低需求；请选择更大的容量");
        if(actual>sectors) throw new IllegalStateException("上游缩容几何产生的区域大于请求值，拒绝继续");
        return new Split(sectors,newEnd,userdataSectors,regionStart,regionEnd);
    }

    static byte[] marker(String token) {
        if(token==null||!token.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("transaction token must be 32 lowercase hex digits");
        byte[] tokenBytes=(new String(MARKER_PREFIX,StandardCharsets.US_ASCII)+token+"\n").getBytes(StandardCharsets.US_ASCII);
        byte[] result=new byte[4096];System.arraycopy(tokenBytes,0,result,0,tokenBytes.length);return result;
    }
    static byte[] bcb() {
        byte[] result=new byte[2048];System.arraycopy(BCB_COMMAND,0,result,0,BCB_COMMAND.length);System.arraycopy(BCB_RECOVERY,0,result,64,BCB_RECOVERY.length);return result;
    }
    static boolean bcbPending(byte[] data) {
        if(data==null||data.length<64+BCB_RECOVERY.length||data.length<BCB_COMMAND.length+1)return false;
        for(int i=0;i<BCB_COMMAND.length;i++)if(data[i]!=BCB_COMMAND[i])return false;
        if(data[BCB_COMMAND.length]!=0)return false;
        outer:for(int i=64;i<=Math.min(data.length-BCB_RECOVERY.length,64+768-BCB_RECOVERY.length);i++) {
            for(int j=0;j<BCB_RECOVERY.length;j++)if(data[i+j]!=BCB_RECOVERY[j])continue outer;
            return true;
        }
        return false;
    }

    static void validate(Map<String,String> r,Facts f,byte[] marker,byte[] bcb,String currentIdentity,String controllerVersion) {
        String state=r.get("state");
        if(!("prepared-request".equals(state)||"prepared".equals(state)||"installed".equals(state))) throw new IllegalArgumentException("事务状态不允许恢复："+state);
        if("prepared-request".equals(state)) throw new IllegalArgumentException("上次准备未完成；不能续接，保留事务与设备现状");
        if(bcbPending(bcb)) throw new IllegalArgumentException("Android recovery 尚未完成清除 userdata；请先启动 recovery/Android，再重新连接");
        for(String k:new String[]{"serial","slot","cid","node","start","disk"})if(!eq(f.value(k),r.get(k)))throw new IllegalArgumentException("设备或启动槽与保存的事务不同："+k);
        long start=positive(r,"start"),target=positive(r,"target"),disk=positive(r,"disk"),usable=positive(r,"usable_end");
        long gptEnd=positive(r,"gpt_end"),regionStart=positive(r,"region_start"),regionEnd=positive(r,"region_end"),need=positive(r,"need");
        if(disk!=f.disk||disk<68||disk>Long.MAX_VALUE/SECTOR)throw new IllegalArgumentException("磁盘容量与事务不符");
        if(f.size!=target)throw new IllegalArgumentException("userdata 容量与事务不符");
        long expectedEnd;
        try { expectedEnd=Math.addExact(start,Math.subtractExact(target,1L)); } catch(ArithmeticException e) { throw new IllegalArgumentException("userdata 事务几何溢出"); }
        if(f.gptFirst!=start||f.gptEnd!=gptEnd||gptEnd!=expectedEnd||f.usableEnd!=usable||usable>=disk-33||gptEnd>usable)
            throw new IllegalArgumentException("GPT geometry 与事务不符");
        long endExclusive;
        try { endExclusive=Math.addExact(regionStart,need); } catch(ArithmeticException e) { throw new IllegalArgumentException("Linux region 几何溢出"); }
        if(regionEnd!=endExclusive||f.regionStart!=regionStart||f.regionEnd!=regionEnd||regionStart<=gptEnd||regionEnd>disk-34||regionEnd>usable+1)
            throw new IllegalArgumentException("Linux region geometry 越界或与事务不符");
        if(target<MIN_ANDROID_SECTORS||need<minimumFor(r.get("system"))||target%ALIGN!=0||need%ALIGN!=0||regionStart%ALIGN!=0||regionEnd%ALIGN!=0)
            throw new IllegalArgumentException("Android/Linux 可用空间低于上游最低要求或恢复几何未按 2 MiB 对齐");
        if(!"installed".equals(state)) {
            byte[] expected=marker(r.get("token"));
            if(marker==null||marker.length!=4096||!Arrays.equals(expected,marker))throw new IllegalArgumentException("准备令牌不匹配；拒绝覆盖 Linux region");
        }
        if(!eq(currentIdentity,r.get("identity")))throw new IllegalArgumentException("安装包或安装器脚本与准备时不同");
        if(!eq(controllerVersion,r.get("controller_version")))throw new IllegalArgumentException("控制 APK 版本与准备事务时不同；拒绝执行不兼容的续接");
    }

    private static long positive(Map<String,String> m,String k) { long v=number(m,k);if(v<=0)throw new IllegalArgumentException("事务字段必须为正数："+k);return v; }
    private static long number(Map<String,String> m,String k) {
        String s=m.get(k);if(s==null||!s.matches("[0-9]{1,19}"))throw new IllegalArgumentException("事务字段无效："+k);
        try{return Long.parseLong(s);}catch(Exception e){throw new IllegalArgumentException("事务字段超出范围："+k);}
    }
    private static boolean eq(String a,String b) { return a==null?b==null:a.equals(b); }

    static final class Facts {
        final Map<String,String> values;
        final long size,disk,gptFirst,gptEnd,usableEnd,regionStart,regionEnd;
        Facts(Map<String,String> values,long size,long disk,long gptFirst,long gptEnd,long usableEnd,long regionStart,long regionEnd) {
            this.values=values;this.size=size;this.disk=disk;this.gptFirst=gptFirst;this.gptEnd=gptEnd;this.usableEnd=usableEnd;this.regionStart=regionStart;this.regionEnd=regionEnd;
        }
        String value(String key) { return values.get(key); }
    }
}
