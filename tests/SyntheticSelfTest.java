package org.linote.mu300controller;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

public final class SyntheticSelfTest {
    private static final int S=512,N=128,E=128;
    private static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
    private static void throwsIt(Runnable r,String m){try{r.run();}catch(RuntimeException expected){return;}throw new AssertionError(m);}
    private static long crc(byte[] b,int off,int n){CRC32 c=new CRC32();c.update(b,off,n);return c.getValue();}
    private static void u32(byte[] b,int o,long v){ByteBuffer.wrap(b,o,4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)v);}
    private static void u64(byte[] b,int o,long v){ByteBuffer.wrap(b,o,8).order(ByteOrder.LITTLE_ENDIAN).putLong(v);}
    private static void header(byte[] b,int off,long cur,long bak,long first,long last,long entLba,long ecrc){
        byte[] sig="EFI PART".getBytes(StandardCharsets.US_ASCII);System.arraycopy(sig,0,b,off,8);
        u32(b,off+8,0x10000);u32(b,off+12,92);u32(b,off+16,0);u64(b,off+24,cur);u64(b,off+32,bak);
        u64(b,off+40,first);u64(b,off+48,last);Arrays.fill(b,off+56,off+72,(byte)0x77);u64(b,off+72,entLba);
        u32(b,off+80,N);u32(b,off+84,E);u32(b,off+88,ecrc);headerCrc(b,off);
    }
    private static void headerCrc(byte[] b,int off){u32(b,off+16,0);u32(b,off+16,crc(b,off,92));}
    private static byte[][] fixture(long disk,boolean extraLast){
        long usable=disk-34;byte[] entries=new byte[N*E];
        entry(entries,0,"system",2048,4095);entry(entries,1,"misc",4096,8191);entry(entries,2,"userdata",10000,extraLast?disk-36:usable);
        if(extraLast)entry(entries,3,"nv_last",disk-35,disk-34);
        long ecrc=crc(entries,0,entries.length);byte[] head=new byte[34*S],tail=new byte[33*S];
        header(head,S,1,disk-1,34,usable,2,ecrc);System.arraycopy(entries,0,head,2*S,entries.length);
        System.arraycopy(entries,0,tail,0,entries.length);header(tail,tail.length-S,disk-1,1,34,usable,disk-1-(entries.length/S),ecrc);
        return new byte[][]{head,tail};
    }
    private static void entry(byte[] a,int i,String name,long first,long last){
        int o=i*E;Arrays.fill(a,o,o+16,(byte)(i+1));Arrays.fill(a,o+16,o+32,(byte)(0x50+i));u64(a,o+32,first);u64(a,o+40,last);
        byte[] n=(name+"\0").getBytes(StandardCharsets.UTF_16LE);System.arraycopy(n,0,a,o+56,n.length);
    }
    private static void updateEntryCrc(byte[][] b,long disk){
        int tailHeader=b[1].length-S;long c=crc(b[0],2*S,N*E);u32(b[0],S+88,c);headerCrc(b[0],S);
        long tc=crc(b[1],0,N*E);u32(b[1],tailHeader+88,tc);headerCrc(b[1],tailHeader);
    }
    private static Map<String,String> txn(){
        Map<String,String> r=new HashMap<>();r.put("state","prepared");r.put("serial","serial1");r.put("slot","a");r.put("cid","0123456789abcdef0123456789abcdef");
        r.put("node","mmcblk0p3");r.put("start","10000");r.put("size","101482496");r.put("disk","122159104");r.put("target","101482496");
        r.put("usable_end","122155070");r.put("region_start","113762304");r.put("region_end","122150912");r.put("need","8388608");
        r.put("gpt_first","10000");r.put("gpt_end","101492495");r.put("system","openwrt");r.put("token","0123456789abcdef0123456789abcdef");
        r.put("identity","abc123");r.put("controller_version","0.3.0");return r;
    }
    private static Map<String,String> facts(){
        Map<String,String> v=new HashMap<>();v.put("serial","serial1");v.put("slot","a");v.put("cid","0123456789abcdef0123456789abcdef");
        v.put("node","mmcblk0p3");v.put("start","10000");v.put("disk","122159104");return v;
    }
    private static Map<String,String> emptyRegion(){
        Map<String,String> r=new HashMap<>();r.put("off","113762304");r.put("size","8388608");r.put("existing","no");r.put("dirty","0");
        r.put("part","0");r.put("part_fs","none");r.put("part_count","0");r.put("foreign_image","0");return r;
    }
    private static MakeRoomProtocol.Facts factSet(Map<String,String> v,long first,long end,long rs,long re){
        return new MakeRoomProtocol.Facts(v,101482496L,122159104L,first,end,122155070L,rs,re);
    }
    public static void main(String[] args){
        long disk=122159104L;byte[][] b=fixture(disk,false);GptTable g=new GptTable(b[0],b[1],disk);
        ok("userdata".equals(g.lastPartition().name),"last partition is userdata");
        GptTable changed=g.resizeUserdataEnd(1000000L);ok(changed.userdataLast().last==1000000L,"resize changes only end sector");
        byte[] badCrc=b[0].clone();badCrc[2*S+20]^=1;throwsIt(()->new GptTable(badCrc,b[1],disk),"bad primary array CRC rejected");
        byte[] bad=b[0].clone();bad[S+32]^=1;headerCrc(bad,S);final byte[] wrongPointer=bad;throwsIt(()->new GptTable(wrongPointer,b[1],disk),"wrong primary backup pointer rejected");
        bad=b[0].clone();bad[S+56]^=1;headerCrc(bad,S);final byte[] wrongGuid=bad;throwsIt(()->new GptTable(wrongGuid,b[1],disk),"mismatched disk GUID rejected");
        byte[] badTail=b[1].clone();badTail[56]^=1;int bh=badTail.length-S;u32(badTail,bh+88,crc(badTail,0,N*E));headerCrc(badTail,bh);
        final byte[] mismatchedEntries=badTail;throwsIt(()->new GptTable(b[0],mismatchedEntries,disk),"different but CRC-valid backup entry array rejected");
        byte[][] overlap=fixture(disk,false);u64(overlap[0],2*S+E+32,3000);u64(overlap[0],2*S+E+40,5000);u64(overlap[1],E+32,3000);u64(overlap[1],E+40,5000);updateEntryCrc(overlap,disk);
        throwsIt(()->new GptTable(overlap[0],overlap[1],disk),"overlapping partitions rejected");
        byte[][] duplicate=fixture(disk,false);byte[] dupEntry=new byte[E];entry(dupEntry,0,"userdata",9000,9500);
        System.arraycopy(dupEntry,0,duplicate[0],2*S+3*E,E);System.arraycopy(dupEntry,0,duplicate[1],3*E,E);updateEntryCrc(duplicate,disk);
        GptTable duplicateTable=new GptTable(duplicate[0],duplicate[1],disk);throwsIt(duplicateTable::userdataLast,"duplicate userdata entries rejected");
        byte[][] extra=fixture(disk,true);GptTable nv=new GptTable(extra[0],extra[1],disk);throwsIt(nv::userdataLast,"refuses a non-userdata last partition");
        GptTable shrunk=g.resizeUserdataEnd(113758207L);shrunk.verifyFreeRegion(113762304L,122150912L);
        throwsIt(()->shrunk.verifyFreeRegion(113758200L,122150912L),"region overlapping userdata rejected");
        throwsIt(()->shrunk.verifyFreeRegion(113762304L,122159072L),"region reaching backup GPT rejected");

        long first=12275712L,usable=122155007L;MakeRoomProtocol.Split s=MakeRoomProtocol.split("4",disk,first,usable,"openwrt");
        ok(s.regionSectors==4L*1024*1024*1024/512&&s.regionSectors%4096==0,"canonical 4 GiB request follows upstream 2 MiB reserve/alignment");
        ok(s.userdataSectors*512>=4L*1024*1024*1024,"Android keeps at least 4 GiB");
        MakeRoomProtocol.Split small=MakeRoomProtocol.split("0.79",disk,first,usable,"openwrt");ok(small.regionBytes>=800L*1024*1024,"0.79 GiB request leaves an OpenWrt-sized region");
        throwsIt(()->MakeRoomProtocol.split("0.79",disk,first,usable,"ubuntu"),"Ubuntu minimum enforced");
        throwsIt(()->MakeRoomProtocol.split("50",disk,first,usable,"openwrt"),"4 GiB Android reserve enforced");
        throwsIt(()->MakeRoomProtocol.split("1e2",disk,first,usable,"openwrt"),"scientific notation rejected");
        throwsIt(()->MakeRoomProtocol.split("-1",disk,first,usable,"openwrt"),"negative requested size rejected");
        throwsIt(()->MakeRoomProtocol.split("4.00001",disk,first,usable,"openwrt"),"more than four decimals rejected");
        MakeRoomProtocol.RegionStatus rs=MakeRoomProtocol.validateEmptyRegionStatus(emptyRegion());
        ok(rs.offset==113762304L&&rs.size==8388608L&&rs.dirty==0,"explicit empty region status accepted");
        Map<String,String> markedRegion=emptyRegion();markedRegion.put("dirty","16");
        ok(MakeRoomProtocol.validateEmptyRegionStatus(markedRegion).dirty==16,"resume may proceed to later exact marker verification despite dirty samples");
        throwsIt(()->MakeRoomProtocol.requireCleanPreparationSample(16,8388608L),"preparation rejects dirty region");
        throwsIt(()->MakeRoomProtocol.requireCleanPreparationSample(-1,16L*1024*1024),"large preparation region rejects missing helper sample");
        MakeRoomProtocol.requireCleanPreparationSample(-1,16L*1024*1024-512); // independent raw sampling handles small regions
        Map<String,String> unknownExisting=emptyRegion();unknownExisting.put("existing","");
        throwsIt(()->MakeRoomProtocol.validateEmptyRegionStatus(unknownExisting),"unknown existing state refused");
        Map<String,String> noExistingField=emptyRegion();noExistingField.remove("existing");final Map<String,String> missingExisting=noExistingField;
        throwsIt(()->MakeRoomProtocol.validateEmptyRegionStatus(missingExisting),"missing existing field refused");
        Map<String,String> occupiedRegion=emptyRegion();occupiedRegion.put("existing","yes");
        throwsIt(()->MakeRoomProtocol.validateEmptyRegionStatus(occupiedRegion),"existing Linux filesystem refused");
        Map<String,String> foreignRegion=emptyRegion();foreignRegion.put("foreign_image","1");
        throwsIt(()->MakeRoomProtocol.validateEmptyRegionStatus(foreignRegion),"foreign Linux image refused");
        Map<String,String> partitionRegion=emptyRegion();partitionRegion.put("part_count","1");
        throwsIt(()->MakeRoomProtocol.validateEmptyRegionStatus(partitionRegion),"foreign mu300root partition refused");
        Map<String,String> fsRegion=emptyRegion();fsRegion.put("part_fs","ext4");
        throwsIt(()->MakeRoomProtocol.validateEmptyRegionStatus(fsRegion),"unknown filesystem state refused");
        Map<String,String> dirtyUnknown=emptyRegion();dirtyUnknown.put("dirty","-2");
        throwsIt(()->MakeRoomProtocol.validateEmptyRegionStatus(dirtyUnknown),"unknown dirty sentinel refused");
        Map<String,String> offInvalid=emptyRegion();offInvalid.put("off","-1");
        throwsIt(()->MakeRoomProtocol.validateEmptyRegionStatus(offInvalid),"negative region offset refused");

        byte[] marker=MakeRoomProtocol.marker("0123456789abcdef0123456789abcdef");
        ok(marker.length==4096&&new String(marker,0,53,StandardCharsets.US_ASCII).startsWith("MU300-PREPARED-V1 "),"token marker format");
        throwsIt(()->MakeRoomProtocol.marker("../not-a-token"),"unsafe token rejected");
        byte[] bcb=MakeRoomProtocol.bcb();ok(MakeRoomProtocol.bcbPending(bcb),"BCB wipe request recognized");
        ok(!MakeRoomProtocol.bcbPending(new byte[2048]),"cleared BCB recognized");
        Map<String,String> r=txn(),v=facts();MakeRoomProtocol.Facts f=factSet(v,10000L,101492495L,113762304L,122150912L);
        MakeRoomProtocol.validate(r,f,marker,new byte[2048],"abc123","0.3.0");
        Map<String,String> wrong=new HashMap<>(v);wrong.put("cid","ffffffffffffffffffffffffffffffff");
        throwsIt(()->MakeRoomProtocol.validate(r,factSet(wrong,10000L,101492495L,113762304L,122150912L),marker,new byte[2048],"abc123","0.3.0"),"mismatched CID refuses resume");
        throwsIt(()->MakeRoomProtocol.validate(r,f,marker,bcb,"abc123","0.3.0"),"pending recovery wipe refuses resume");
        Map<String,String> request=new HashMap<>(r);request.put("state","prepared-request");
        throwsIt(()->MakeRoomProtocol.validate(request,f,marker,new byte[2048],"abc123","0.3.0"),"incomplete transaction refuses resume");
        byte[] badMarker=marker.clone();badMarker[30]^=1;
        throwsIt(()->MakeRoomProtocol.validate(r,f,badMarker,new byte[2048],"abc123","0.3.0"),"token marker mismatch refuses resume");
        throwsIt(()->MakeRoomProtocol.validate(r,f,marker,new byte[2048],"different","0.3.0"),"tool/package identity mismatch refuses resume");
        throwsIt(()->MakeRoomProtocol.validate(r,f,marker,new byte[2048],"abc123","0.4.0"),"controller version mismatch refuses resume");
        Map<String,String> overflow=new HashMap<>(r);overflow.put("region_start","9223372036854775807");overflow.put("region_end","9223372036854775807");overflow.put("need","1");
        throwsIt(()->MakeRoomProtocol.validate(overflow,f,marker,new byte[2048],"abc123","0.3.0"),"region arithmetic overflow refused");
        Map<String,String> malformed=new HashMap<>(r);malformed.put("target","-1");
        throwsIt(()->MakeRoomProtocol.validate(malformed,f,marker,new byte[2048],"abc123","0.3.0"),"negative transaction number refused");
        Map<String,String> wrongGeometry=new HashMap<>(r);wrongGeometry.put("gpt_end","101492494");
        throwsIt(()->MakeRoomProtocol.validate(wrongGeometry,f,marker,new byte[2048],"abc123","0.3.0"),"GPT geometry mismatch refused");
        System.out.println("SyntheticSelfTest: all checks passed (no USB or device access).");
    }
}
