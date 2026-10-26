package org.linote.mu300controller;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Controller-phone transaction, backup and resume verifier for the upstream experimental userdata shrink flow. */
final class MakeRoomTransaction {
    private static final String[] PARTITIONS = {"boot", "uboot", "sml", "teecfg", "trustos", "vbmeta"};
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final long MAX_PARTITION_BACKUP = 512L * 1024 * 1024;

    static final class Record {
        final File directory, transaction, zip;
        final JSONObject json;
        Record(File directory, File transaction, File zip, JSONObject json) { this.directory=directory; this.transaction=transaction; this.zip=zip; this.json=json; }
        String state() { return json.optString("state", ""); }
        String zipName() { return json.optString("zip_name", ""); }
        String zipSha() { return json.optString("zip_sha256", ""); }
        String identity() { return json.optString("identity", ""); }
        String system() { return json.optString("system", ""); }
    }

    static final class Prepared {
        final Record record;
        final MakeRoomProtocol.Split split;
        Prepared(Record record, MakeRoomProtocol.Split split) { this.record=record; this.split=split; }
    }

    private final Context context;
    private final AdbConnection conn;
    MakeRoomTransaction(Context context, AdbConnection conn) { this.context=context.getApplicationContext(); this.conn=conn; }

    Prepared prepare(MakeRoomPreflight.Result p, File selectedZip, String zipName, String expectedZipSha,
                     String system, String gibText) throws Exception {
        if (selectedZip==null || !selectedZip.isFile() || selectedZip.length()<=0) throw new IllegalStateException("已校验的 Magisk ZIP 不存在");
        if (!InstallerProtocol.safeZipName(zipName) || !InstallerProtocol.safeSha256(expectedZipSha)) throw new IllegalArgumentException("ZIP 名称或 SHA-256 无效");
        String localZipSha=shaFile(selectedZip);
        if (!expectedZipSha.equals(localZipSha)) throw new IllegalStateException("本地 ZIP SHA-256 与官方校验值不一致；未写入设备。");
        if (!system.equals(systemFromManifest(selectedZip))) throw new IllegalStateException("所选 ZIP 的系统与安装问卷不一致。");
        MakeRoomProtocol.Split split=MakeRoomProtocol.split(gibText,p.diskSectors,p.userdata.first,p.gpt.usableEnd(),system);
        GptTable modified=p.gpt.resizeUserdataEnd(split.newUserdataEnd);
        modified.verifyFreeRegion(split.regionStart,split.regionEnd);
        String identity=identity(selectedZip);
        String token=randomToken();

        long expectedBackups=34L*512+33L*512+4096;
        Map<String,Long> partitionSizes=new java.util.HashMap<>();
        for(String part:PARTITIONS) for(String slot:new String[]{"a","b"}) {
            String name=part+"_"+slot; long bytes=partitionBytes(name);
            if(bytes<=0||bytes>MAX_PARTITION_BACKUP) throw new IllegalStateException("上游必需备份分区 "+name+" 的大小无效或超过 512 MiB；未写入设备。");
            partitionSizes.put(name,bytes); expectedBackups=Math.addExact(expectedBackups,bytes);
        }
        if(expectedBackups>2L*1024*1024*1024) throw new IllegalStateException("上游必需备份超过 2 GiB；设备分区异常，未写入设备。");
        long needLocal=Math.addExact(Math.addExact(selectedZip.length(),expectedBackups),256L*1024*1024);
        if(context.getFilesDir().getUsableSpace()<needLocal) throw new IllegalStateException("控制手机私有存储不足：至少需要 "+formatBytes(needLocal)+" 可用空间（含完整备份、ZIP 和 256 MiB 余量）；未写入设备。");

        File root=new File(context.getFilesDir(),"make-room");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法在控制手机建立私有备份目录");
        String suffix=String.format(Locale.ROOT,"%08d-%s-%s",System.currentTimeMillis(),p.cid.substring(24),token.substring(0,8));
        File dir=new File(root,suffix);
        if (!dir.mkdir()) throw new IllegalStateException("无法建立本地缩容事务目录");
        File backup=new File(dir,"backup"); if(!backup.mkdir()) throw new IllegalStateException("无法建立 eMMC 备份目录");
        File localZip=new File(dir,"payload.zip");
        copyFile(selectedZip,localZip);
        if(!expectedZipSha.equals(shaFile(localZip))) throw new IllegalStateException("保存到控制手机的安装包校验不匹配");

        // Read-only captures and all full partition backups happen before the transaction or any device write.
        File gptHead=new File(backup,"gpt.head"), gptTail=new File(backup,"gpt.tail");
        writeBytes(gptHead,p.gpt.head()); writeBytes(gptTail,p.gpt.tail());
        File misc=new File(backup,"misc-head.bin");
        byte[] miscBytes=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/by-name/misc bs=4096 count=1 2>/dev/null'",4096);
        writeBytes(misc,miscBytes);
        long backupTotal=gptHead.length()+gptTail.length()+misc.length();
        List<File> images=new ArrayList<>();
        for(String part:PARTITIONS) for(String slot:new String[]{"a","b"}) {
            String name=part+"_"+slot; File f=new File(backup,name+".img");
            long got=pullAndVerifyPartition(name,f,partitionSizes.get(name));
            backupTotal=Math.addExact(backupTotal,got);
            if(backupTotal>2L*1024*1024*1024) throw new IllegalStateException("上游必需备份超过 2 GiB；控制手机空间或设备分区异常，未写入设备。");
            images.add(f);
        }
        writeChecksums(backup,gptHead,gptTail,misc,images);
        verifyChecksums(backup);

        JSONObject record=new JSONObject();
        record.put("format",1); record.put("state","prepared-request"); record.put("created",java.time.Instant.now().toString());
        record.put("serial",p.serial); record.put("slot",p.slot); record.put("cid",p.cid); record.put("node",p.node);
        record.put("start",p.userdata.first); record.put("size",p.userdataSize); record.put("disk",p.diskSectors);
        record.put("target",split.userdataSectors); record.put("need",split.regionSectors); record.put("usable_end",p.gpt.usableEnd());
        record.put("gpt_first",p.userdata.first); record.put("gpt_end",split.newUserdataEnd);
        record.put("region_start",split.regionStart); record.put("region_end",split.regionEnd);
        record.put("token",token); record.put("identity",identity); record.put("controller_version",BuildInfo.VERSION_NAME);
        record.put("crypto",p.crypto); record.put("crypto_state",p.cryptoState); record.put("crypto_type",p.cryptoType);
        record.put("backup",backup.getAbsolutePath()); record.put("zip_name",zipName); record.put("zip_sha256",expectedZipSha);
        record.put("system",system); record.put("linux_gib_requested",gibText); record.put("payload","payload.zip");
        File txFile=new File(dir,"transaction.json"); atomicSave(txFile,record); // durable before first device write

        File newHead=new File(dir,"new.head"), newTail=new File(dir,"new.tail"), markerFile=new File(dir,"marker.bin"), bcbFile=new File(dir,"bcb.bin");
        writeBytes(newHead,modified.head()); writeBytes(newTail,modified.tail());
        writeBytes(markerFile,MakeRoomProtocol.marker(token)); writeBytes(bcbFile,MakeRoomProtocol.bcb());
        String tmpDir="/data/local/tmp/mu300-room-"+token.substring(0,12);
        boolean blockWritesStarted=false;
        try {
            int mk=conn.shell("mkdir "+MakeRoomPreflight.quote(tmpDir)+" && chmod 755 "+MakeRoomPreflight.quote(tmpDir)+" && [ ! -L "+MakeRoomPreflight.quote(tmpDir)+" ]",null,30000);
            if(mk!=0) throw new IllegalStateException("无法建立缩容临时传输目录");
            File[] locals={newHead,newTail,markerFile,bcbFile};
            String[] remotes={tmpDir+"/new.head",tmpDir+"/new.tail",tmpDir+"/marker.bin",tmpDir+"/bcb.bin"};
            int[] modes={0644,0644,0644,0644};
            conn.push(locals,remotes,modes,null);
            for(int i=0;i<locals.length;i++) if(!shaDevice(remotes[i]).equals(shaFile(locals[i])))
                throw new IllegalStateException("缩容文件上传校验失败："+new File(remotes[i]).getName()+"；任何分区均未写入。");
            byte[] beforeMisc=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/by-name/misc bs=4096 count=1 2>/dev/null'",4096);
            byte[] abBlock=Arrays.copyOfRange(beforeMisc,2048,2080);

            // Strict upstream order: backup GPT first, then primary GPT. Never reverse these writes.
            blockWritesStarted=true;
            runRoot("dd if="+q(tmpDir+"/new.tail")+" of=/dev/block/mmcblk0 bs=512 seek="+(p.diskSectors-33)+" conv=fsync 2>/dev/null");
            runRoot("dd if="+q(tmpDir+"/new.head")+" of=/dev/block/mmcblk0 bs=512 conv=fsync 2>/dev/null");
            byte[] readHead=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/mmcblk0 bs=512 count=34 2>/dev/null'",34*512);
            byte[] readTail=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/mmcblk0 bs=512 skip="+(p.diskSectors-33)+" count=33 2>/dev/null'",33*512);
            GptTable readBack=new GptTable(readHead,readTail,p.diskSectors);
            if(readBack.userdataLast().last!=split.newUserdataEnd) throw new IllegalStateException("GPT 写入后 userdata end 与计划不符；未写入令牌或 recovery 请求。不要关机。");
            readBack.verifyFreeRegion(split.regionStart,split.regionEnd);

            runRoot("dd if="+q(tmpDir+"/marker.bin")+" of=/dev/block/mmcblk0 bs=512 seek="+split.regionStart+" count=8 conv=notrunc,fsync 2>/dev/null");
            byte[] markerRead=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/mmcblk0 bs=512 skip="+split.regionStart+" count=8 2>/dev/null'",4096);
            if(!Arrays.equals(markerRead,MakeRoomProtocol.marker(token))) throw new IllegalStateException("GPT 已更改但 token read-back 失败；misc 尚未改变，Android 数据仍未重置。不要关机；保留 transaction.json 和备份。");

            runRoot("dd if="+q(tmpDir+"/bcb.bin")+" of=/dev/block/by-name/misc bs=2048 count=1 conv=notrunc,fsync 2>/dev/null");
            byte[] afterMisc=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/by-name/misc bs=4096 count=1 2>/dev/null'",4096);
            if(!Arrays.equals(Arrays.copyOfRange(afterMisc,0,2048),MakeRoomProtocol.bcb())
                    || !Arrays.equals(Arrays.copyOfRange(afterMisc,2048,2080),abBlock))
                throw new IllegalStateException("GPT 已更改；misc recovery 请求或 A/B block 未能按预期读回。保留设备现状，不要关机。");

            runRoot("sync; rm -f "+q(tmpDir+"/new.head")+" "+q(tmpDir+"/new.tail")+" "+q(tmpDir+"/marker.bin")+" "+q(tmpDir+"/bcb.bin")+"; rmdir "+q(tmpDir));
            record.put("state","prepared"); record.put("prepared_at",java.time.Instant.now().toString()); atomicSave(txFile,record);
            return new Prepared(new Record(dir,txFile,localZip,record),split);
        } catch(Exception e) {
            if(!blockWritesStarted) {
                try { conn.shell("rm -rf "+MakeRoomPreflight.quote(tmpDir),null,10000); } catch(Exception ignored) { }
            }
            throw e;
        }
    }

    Record findWaiting(String serial,String cid) throws Exception {
        File root=new File(context.getFilesDir(),"make-room"); File[] dirs=root.listFiles(); if(dirs==null) return null;
        List<Record> found=new ArrayList<>();
        for(File d:dirs) {
            File tx=new File(d,"transaction.json"); if(!d.isDirectory()||!tx.isFile()) continue;
            try {
                JSONObject j=new JSONObject(readText(tx));
                String state=j.optString("state", "");
                if(!("prepared".equals(state)||"prepared-request".equals(state))) continue;
                if(!serial.equals(j.optString("serial"))||!cid.equals(j.optString("cid"))) continue;
                File zip=new File(d,j.optString("payload","payload.zip"));
                found.add(new Record(d,tx,zip,j));
            } catch(Exception ignored) { }
        }
        if(found.isEmpty()) return null;
        Collections.sort(found,(a,b)->Long.compare(b.transaction.lastModified(),a.transaction.lastModified()));
        return found.get(0);
    }

    void validateResume(Record r, MakeRoomPreflight.Result p, JSONObject status) throws Exception {
        if(!r.transaction.isFile()||!r.zip.isFile()) throw new IllegalStateException("本地缩容事务或保存的原始 ZIP 缺失；不继续。");
        File backup=new File(r.directory,"backup");
        if(!backup.isDirectory()||!backup.getCanonicalPath().equals(new File(r.json.optString("backup","")).getCanonicalPath()))
            throw new IllegalStateException("事务备份目录与本地事务记录不符；不继续。");
        verifyChecksums(backup);
        String zipSha=shaFile(r.zip);
        if(!r.zipSha().equals(zipSha)) throw new IllegalStateException("本地保存的 ZIP 与事务 SHA-256 不符；不继续。");
        if(!r.system().equals(systemFromManifest(r.zip))) throw new IllegalStateException("恢复事务系统与本地 ZIP manifest 不符；不继续。");
        String identity=identity(r.zip);
        long disk=p.diskSectors;
        byte[] head=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/mmcblk0 bs=512 count=34 2>/dev/null'",34*512);
        byte[] tail=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/mmcblk0 bs=512 skip="+(disk-33)+" count=33 2>/dev/null'",33*512);
        GptTable g=new GptTable(head,tail,disk); GptTable.Partition ud=g.userdataLast();
        JSONObject region=status.optJSONObject("region"); if(region==null) throw new IllegalStateException("helper 未报告恢复后的 region");
        long off=region.optLong("off",-1), bytes=region.optLong("size",-1);
        if(off<0||bytes<=0||off%512!=0||bytes%512!=0) throw new IllegalStateException("恢复后的 region offset/size 无效");
        long endBytes;
        try { endBytes=Math.addExact(off,bytes); } catch(ArithmeticException e) { throw new IllegalStateException("恢复后的 region offset+size 溢出"); }
        long regionStart=off/512, regionEnd=endBytes/512;
        g.verifyFreeRegion(regionStart,regionEnd);
        if(status.optLong("last_end",-1)!=Math.addExact(ud.last,1L)) throw new IllegalStateException("helper last_end 与恢复后 GPT 最后一分区不符");
        JSONObject diskInfo=status;
        if(diskInfo.optLong("disk",-1)!=disk) throw new IllegalStateException("helper 与重新读取的 eMMC sector 数不符");
        long oldRegionStart=longField(r.json,"region_start");
        byte[] marker=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/mmcblk0 bs=512 skip="+oldRegionStart+" count=8 2>/dev/null'",4096);
        byte[] misc=MakeRoomPreflight.readRaw(conn,"su -c 'dd if=/dev/block/by-name/misc bs=2048 count=1 2>/dev/null'",2048);
        Map<String,String> f=new java.util.HashMap<>();
        f.put("serial",p.serial); f.put("slot",p.slot); f.put("cid",p.cid); f.put("node",p.node);
        f.put("start",Long.toString(p.userdataStart)); f.put("disk",Long.toString(p.diskSectors));
        MakeRoomProtocol.Facts facts=new MakeRoomProtocol.Facts(f,p.userdataSize,p.diskSectors,ud.first,ud.last,g.usableEnd(),regionStart,regionEnd);
        Map<String,String> txMap=new java.util.HashMap<>();
        for(java.util.Iterator<String> it=r.json.keys();it.hasNext();) { String k=it.next(); Object v=r.json.opt(k); txMap.put(k,String.valueOf(v)); }
        MakeRoomProtocol.validate(txMap,facts,marker,misc,identity,BuildInfo.VERSION_NAME);
    }

    void markInstalled(Record r) throws Exception {
        r.json.put("state","installed"); r.json.put("installed_at",java.time.Instant.now().toString()); atomicSave(r.transaction,r.json);
    }

    private long partitionBytes(String name) throws Exception {
        if(!name.matches("[a-z0-9]+_[ab]")) throw new IllegalArgumentException("invalid partition name");
        String script="set -e; d=$(readlink -f /dev/block/by-name/"+name+"); n=${d##*/}; case $n in mmcblk0p[0-9]*) ;; *) exit 41 ;; esac; cat /sys/class/block/$n/size";
        List<String> lines=new ArrayList<>(); int rc=conn.shell("su -c "+MakeRoomPreflight.quote(script),lines::add,30000);
        if(rc!=0||lines.isEmpty()) throw new IllegalStateException("无法读取 "+name+" 分区大小；未写入设备。");
        try { long sectors=Long.parseLong(lines.get(lines.size()-1).trim()); return Math.multiplyExact(sectors,512L); }
        catch(Exception e) { throw new IllegalStateException("分区大小无效："+name); }
    }

    private long pullAndVerifyPartition(String name,File dest,long expectedBytes) throws Exception {
        String path="/dev/block/by-name/"+name;
        long n;
        try(OutputStream out=new FileOutputStream(dest,false)) { n=conn.execRaw("su -c 'cat "+path+"'",out,180000); }
        if(n!=expectedBytes||n<=0||n>MAX_PARTITION_BACKUP||dest.length()!=n) throw new IllegalStateException("读取 "+name+" 备份长度与 sysfs 不符；尚未写入设备。");
        String remote=shaDevice(path), local=shaFile(dest);
        if(!local.equals(remote)) throw new IllegalStateException(name+" 本地备份与设备 SHA-256 不同；尚未写入设备。");
        return n;
    }

    private String shaDevice(String path) throws Exception {
        if(!path.matches("/[A-Za-z0-9_./-]+")||path.contains("..")) throw new IllegalArgumentException("unsafe device path");
        List<String> lines=new ArrayList<>(); int rc=conn.shell("su -c "+MakeRoomPreflight.quote("sha256sum "+q(path)),lines::add,120000);
        if(rc!=0) throw new IllegalStateException("设备端 sha256sum 失败："+path);
        for(String line:lines) { java.util.regex.Matcher m=java.util.regex.Pattern.compile("^\\s*([0-9a-f]{64})\\s+.*$").matcher(line.trim()); if(m.matches()) return m.group(1); }
        throw new IllegalStateException("设备端未返回有效 SHA-256："+path);
    }

    private void runRoot(String command) throws Exception {
        List<String> lines=new ArrayList<>(); int rc=conn.shell("su -c "+MakeRoomPreflight.quote(command),lines::add,120000);
        if(rc!=0) throw new IllegalStateException("设备 root 写入命令失败，退出码 "+rc+"；保留事务与本地备份。"+(lines.isEmpty()?"":" "+lines.get(lines.size()-1)));
    }

    private static String identity(File zipFile) throws Exception {
        String[] tools={"tools/resize-last-partition.py","tools/storage.sh","tools/android-install.sh","tools/android-mount-mu300root.sh","boot/init"};
        TreeMap<String,String> files=new TreeMap<>();
        try(ZipFile z=new ZipFile(zipFile)) {
            for(String tool:tools) {
                ZipEntry e=z.getEntry("computer/"+tool);
                files.put(tool,e==null?"missing":shaStream(z.getInputStream(e)));
            }
            for(Map.Entry<String,String> e:files.entrySet()) if("missing".equals(e.getValue())) throw new IllegalStateException("ZIP 的 computer/ 缺少 resume identity 工具："+e.getKey());
            ZipEntry manifest=z.getEntry("mu300/manifest");
            if(manifest==null) throw new IllegalStateException("Magisk ZIP 缺少 mu300/manifest");
            Map<String,String> m=parseManifest(z.getInputStream(manifest));
            TreeMap<String,String> pkg=new TreeMap<>();
            String kernelAsset=req(m,"KERNEL_ASSET"),rootfsAsset=req(m,"ROOTFS_ASSET"),kernelSha=req(m,"SHA256_KERNEL"),rootfsSha=req(m,"SHA256_ROOTFS");
            if(!kernelAsset.matches("[A-Za-z0-9._-]{1,128}")||!rootfsAsset.matches("[A-Za-z0-9._-]{1,128}")
                    ||kernelAsset.contains("..")||rootfsAsset.contains("..")||!kernelSha.matches("[0-9a-f]{64}")||!rootfsSha.matches("[0-9a-f]{64}"))
                throw new IllegalStateException("ZIP manifest payload 名或 SHA-256 无效");
            pkg.put(kernelAsset,kernelSha);pkg.put(rootfsAsset,rootfsSha);
            StringBuilder json=new StringBuilder("{\"files\":{");
            appendJsonMap(json,files); json.append("},\"format\":1,\"layout\":\"mu300-free-region\",\"package\":{"); appendJsonMap(json,pkg); json.append("}}");
            return sha(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String systemFromManifest(File zipFile) throws Exception {
        try(ZipFile z=new ZipFile(zipFile)) {
            ZipEntry e=z.getEntry("mu300/manifest"); if(e==null) throw new IllegalStateException("ZIP 缺少 mu300/manifest");
            return req(parseManifest(z.getInputStream(e)),"SYSTEM");
        }
    }
    private static Map<String,String> parseManifest(InputStream in) throws Exception {
        Map<String,String> m=new java.util.HashMap<>();
        try(InputStream x=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) { byte[] b=new byte[8192]; int n; while((n=x.read(b))!=-1) {if(out.size()+n>65536)throw new IllegalStateException("manifest 超过大小限制");out.write(b,0,n);} for(String line:new String(out.toByteArray(),StandardCharsets.UTF_8).split("\\r?\\n")){int i=line.indexOf('=');if(i>0){String key=line.substring(0,i),value=line.substring(i+1).trim();if(m.put(key,value)!=null)throw new IllegalStateException("manifest 出现重复字段："+key);}} }
        return m;
    }
    private static void appendJsonMap(StringBuilder b,TreeMap<String,String> m) {
        boolean first=true; for(Map.Entry<String,String> e:m.entrySet()) { if(!first)b.append(','); first=false; b.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue())); }
    }
    private static String jsonString(String s) {
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\b':b.append("\\b");break;case '\f':b.append("\\f");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<0x20)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}
        return b.append('"').toString();
    }
    private static String req(Map<String,String> m,String key) { String v=m.get(key); if(v==null||v.isEmpty()) throw new IllegalStateException("manifest 缺少 "+key); return v; }

    private static void writeChecksums(File backup,File head,File tail,File misc,List<File> images) throws Exception {
        File sums=new File(backup,"SHA256SUMS");
        try(FileOutputStream out=new FileOutputStream(sums,false)) {
            List<File> all=new ArrayList<>(); all.add(head); all.add(tail); all.add(misc); all.addAll(images);
            for(File f:all) { String line=shaFile(f)+"  "+f.getName()+"\n"; out.write(line.getBytes(StandardCharsets.US_ASCII)); }
            out.getFD().sync();
        }
    }
    private static void verifyChecksums(File backup) throws Exception {
        String text=readText(new File(backup,"SHA256SUMS")); int count=0;
        for(String line:text.split("\\r?\\n")) { if(line.isEmpty())continue; int i=line.indexOf("  "); if(i!=64)throw new IllegalStateException("SHA256SUMS 格式损坏");
            String name=line.substring(i+2); if(!name.matches("[A-Za-z0-9._-]+")||name.contains(".."))throw new IllegalStateException("备份文件名异常");
            File f=new File(backup,name); if(!f.isFile()||!line.substring(0,64).equals(shaFile(f)))throw new IllegalStateException("本地备份校验失败："+name); count++; }
        if(count!=15) throw new IllegalStateException("控制手机备份项目数量不完整："+count+"/15");
    }

    private static void atomicSave(File file,JSONObject j) throws Exception {
        File tmp=new File(file.getParentFile(),file.getName()+".tmp"); byte[] data=(j.toString(2)+"\n").getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(tmp,false)) { out.write(data); out.getFD().sync(); }
        if(!tmp.renameTo(file)) { if(file.exists()&&!file.delete())throw new IllegalStateException("无法替换事务记录"); if(!tmp.renameTo(file))throw new IllegalStateException("无法原子保存事务记录"); }
    }
    private static void writeBytes(File file,byte[] bytes) throws Exception { try(FileOutputStream out=new FileOutputStream(file,false)){out.write(bytes);out.getFD().sync();} }
    private static void copyFile(File src,File dst) throws Exception { try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(dst,false)){byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);} }
    private static String readText(File f) throws Exception { try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IllegalStateException("事务文件过大");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);} }
    static String shaFile(File f) throws Exception { MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return hex(md.digest()); }
    private static String shaStream(InputStream in) throws Exception { MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream x=in){byte[] b=new byte[64*1024];int n;while((n=x.read(b))!=-1)md.update(b,0,n);}return hex(md.digest()); }
    private static String sha(byte[] b) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(b)); }
    private static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte x:b)s.append(HEX[(x>>>4)&15]).append(HEX[x&15]); return s.toString(); }
    private static String randomToken() { byte[] b=new byte[16];new SecureRandom().nextBytes(b);return hex(b); }
    private static long longField(JSONObject j,String k) { long v=j.optLong(k,-1);if(v<0)throw new IllegalStateException("事务字段无效："+k);return v; }
    private static String formatBytes(long n) { if(n>=1024L*1024*1024)return String.format(Locale.ROOT,"%.2f GiB",n/(1024.0*1024*1024));return String.format(Locale.ROOT,"%.0f MiB",n/(1024.0*1024)); }
    private static String q(String s) { return "'"+s.replace("'","'\\''")+"'"; }
}
