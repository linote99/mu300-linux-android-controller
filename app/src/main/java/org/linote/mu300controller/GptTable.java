package org.linote.mu300controller;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;

/** Port of tools/resize-last-partition.py with additional fail-closed GPT invariants. */
final class GptTable {
    static final int SECTOR = 512;
    static final int HEAD_SECTORS = 34;
    static final int TAIL_SECTORS = 33;
    private static final byte[] SIGNATURE = new byte[]{'E','F','I',' ','P','A','R','T'};

    static final class Partition {
        final int index;
        final long first, last;
        final String name;
        Partition(int index, long first, long last, String name) { this.index=index; this.first=first; this.last=last; this.name=name; }
    }

    private final byte[] head, tail;
    private final long diskSectors;
    private final Header primary, backup;
    private final int primaryEntriesOffset, backupEntriesOffset, entryBytes;
    private final List<Partition> partitions;

    private static final class Header {
        final int off, size, entriesCount, entrySize;
        final long revision, current, backup, first, last, entriesLba;
        final byte[] diskGuid;
        Header(int off, int size, long revision, long current, long backup, long first, long last,
               long entriesLba, int entriesCount, int entrySize, byte[] diskGuid) {
            this.off=off; this.size=size; this.revision=revision; this.current=current; this.backup=backup;
            this.first=first; this.last=last; this.entriesLba=entriesLba; this.entriesCount=entriesCount;
            this.entrySize=entrySize; this.diskGuid=diskGuid;
        }
    }

    GptTable(byte[] head, byte[] tail, long diskSectors) {
        if (head == null || tail == null || head.length != HEAD_SECTORS * SECTOR || tail.length != TAIL_SECTORS * SECTOR)
            throw new IllegalArgumentException("GPT dump must be exactly 34/33 sectors");
        if (diskSectors < 68 || diskSectors > Long.MAX_VALUE / SECTOR) throw new IllegalArgumentException("disk sector count is implausible");
        this.head=head.clone(); this.tail=tail.clone(); this.diskSectors=diskSectors;
        int backupHeaderOffset=this.tail.length-SECTOR;
        this.primary=parseHeader(this.head,SECTOR,"primary");
        this.backup=parseHeader(this.tail,backupHeaderOffset,"backup");
        if(primary.revision!=0x10000L||backup.revision!=0x10000L) throw new IllegalArgumentException("unsupported GPT revision");
        if(primary.first!=backup.first||primary.last!=backup.last||primary.entriesCount!=backup.entriesCount||primary.entrySize!=backup.entrySize)
            throw new IllegalArgumentException("primary and backup GPT headers disagree about usable area or entry layout");
        if(primary.current!=1||primary.backup!=diskSectors-1||backup.current!=diskSectors-1||backup.backup!=1)
            throw new IllegalArgumentException("GPT header current/backup pointers are inconsistent");
        if(!equal(primary.diskGuid,backup.diskGuid)) throw new IllegalArgumentException("primary and backup GPT disk GUIDs differ");
        if(primary.entriesCount<=0||primary.entriesCount>4096||primary.entrySize<128||primary.entrySize>4096||primary.entrySize%8!=0)
            throw new IllegalArgumentException("unsupported GPT entry array dimensions");
        long bytes=Math.multiplyExact((long)primary.entriesCount,(long)primary.entrySize);
        if(bytes>Integer.MAX_VALUE) throw new IllegalArgumentException("GPT entry array too large");
        this.entryBytes=(int)bytes;
        long entrySectors=(bytes+SECTOR-1)/SECTOR;
        if(primary.entriesLba!=2||primary.entriesLba+entrySectors>primary.first)
            throw new IllegalArgumentException("primary GPT entry array is not in its canonical location");
        long expectedBackupEntries=diskSectors-1-entrySectors;
        if(backup.entriesLba!=expectedBackupEntries||backup.entriesLba+entrySectors!=diskSectors-1)
            throw new IllegalArgumentException("backup GPT entry array is not immediately before its header");
        if(primary.first<2+entrySectors||primary.last<primary.first||primary.last>=backup.entriesLba)
            throw new IllegalArgumentException("GPT usable range is invalid");
        long pOffset=Math.multiplyExact(primary.entriesLba,(long)SECTOR);
        if(pOffset<0||pOffset+bytes>this.head.length) throw new IllegalArgumentException("primary GPT entries do not fit in dump");
        this.primaryEntriesOffset=(int)pOffset;
        this.backupEntriesOffset=backupHeaderOffset-entryBytes;
        if(backupEntriesOffset<0) throw new IllegalArgumentException("backup GPT entries do not fit in dump");
        checkEntryCrc(this.head,primaryEntriesOffset,entryBytes,u32(this.head,primary.off+88),"primary");
        checkEntryCrc(this.tail,backupEntriesOffset,entryBytes,u32(this.tail,backup.off+88),"backup");
        if(!rangeEquals(this.head,primaryEntriesOffset,this.tail,backupEntriesOffset,entryBytes))
            throw new IllegalArgumentException("primary and backup GPT entry arrays differ");
        List<Partition> found=new ArrayList<>();
        for(int i=0;i<primary.entriesCount;i++) {
            int off=primaryEntriesOffset+i*primary.entrySize;
            if(allZero(this.head,off,16)) continue;
            long first=u64(this.head,off+32),last=u64(this.head,off+40);
            if(first<primary.first||last<first||last>primary.last) throw new IllegalArgumentException("partition bounds outside GPT usable range at entry "+i);
            String name=new String(this.head,off+56,Math.min(72,primary.entrySize-56),StandardCharsets.UTF_16LE);
            int nul=name.indexOf('\0'); if(nul>=0) name=name.substring(0,nul);
            found.add(new Partition(i,first,last,name));
        }
        if(found.isEmpty()) throw new IllegalArgumentException("GPT has no partitions");
        List<Partition> byStart=new ArrayList<>(found); byStart.sort(Comparator.comparingLong(p->p.first));
        for(int i=1;i<byStart.size();i++) if(byStart.get(i).first<=byStart.get(i-1).last)
            throw new IllegalArgumentException("GPT partitions overlap");
        this.partitions=Collections.unmodifiableList(found);
    }

    List<Partition> partitions() { return partitions; }
    long firstUsable() { return primary.first; }
    long usableEnd() { return primary.last; }
    long diskSectors() { return diskSectors; }
    long backupEntriesLba() { return backup.entriesLba; }
    byte[] head() { return head.clone(); }
    byte[] tail() { return tail.clone(); }

    Partition lastPartition() {
        Partition last=null;
        for(Partition p:partitions) if(last==null||p.last>last.last) last=p;
        return last;
    }

    Partition userdataLast() {
        Partition p=lastPartition();
        if(p==null||!"userdata".equals(p.name)) throw new IllegalArgumentException("last GPT partition is not userdata; refusing to move any other partition");
        int count=0;for(Partition candidate:partitions)if("userdata".equals(candidate.name))count++;
        if(count!=1)throw new IllegalArgumentException("GPT has multiple userdata entries; refusing ambiguous resize");
        return p;
    }

    /** startSector inclusive, endSector exclusive; only unpartitioned space before the backup GPT is accepted. */
    void verifyFreeRegion(long startSector,long endSector) {
        if(startSector<0||endSector<=startSector||endSector>diskSectors-1) throw new IllegalArgumentException("region bounds are invalid or outside the disk");
        Partition last=lastPartition();
        if(startSector<last.last+1||endSector>backup.entriesLba) throw new IllegalArgumentException("region is not wholly after the final partition and before backup GPT");
        if(endSector-1>usableEnd()) throw new IllegalArgumentException("region extends beyond GPT usable sectors");
        for(Partition p:partitions) if(startSector<=p.last&&endSector-1>=p.first) throw new IllegalArgumentException("region overlaps GPT partition "+p.name);
    }

    GptTable resizeUserdataEnd(long newLast) {
        Partition userdata=userdataLast();
        if(newLast>usableEnd()||newLast<=userdata.first||newLast>=userdata.last) throw new IllegalArgumentException("new userdata end is outside the original GPT partition");
        byte[] h=head.clone(),t=tail.clone();
        putU64(h,primaryEntriesOffset+userdata.index*primary.entrySize+40,newLast);
        putU64(t,backupEntriesOffset+userdata.index*backup.entrySize+40,newLast);
        long pCrc=crc(h,primaryEntriesOffset,entryBytes),bCrc=crc(t,backupEntriesOffset,entryBytes);
        if(pCrc!=bCrc) throw new IllegalStateException("primary and backup entry arrays differ after edit");
        putU32(h,primary.off+88,pCrc);putU32(t,backup.off+88,bCrc);
        setHeaderCrc(h,primary);setHeaderCrc(t,backup);
        GptTable checked=new GptTable(h,t,diskSectors);
        if(checked.userdataLast().last!=newLast) throw new IllegalStateException("edited GPT failed read-back validation");
        return checked;
    }

    private static Header parseHeader(byte[] b,int off,String which) {
        if(off<0||off+92>b.length) throw new IllegalArgumentException(which+" GPT header is truncated");
        for(int i=0;i<SIGNATURE.length;i++) if(b[off+i]!=SIGNATURE[i]) throw new IllegalArgumentException(which+" GPT signature is not EFI PART");
        int size=(int)u32(b,off+12);
        if(size<92||size>SECTOR||off+size>b.length) throw new IllegalArgumentException(which+" GPT header size is invalid");
        long expected=u32(b,off+16);
        byte[] header=new byte[size];System.arraycopy(b,off,header,0,size);putU32(header,16,0);
        if(crc(header,0,header.length)!=expected) throw new IllegalArgumentException(which+" GPT header CRC mismatch");
        if(u32(b,off+20)!=0) throw new IllegalArgumentException(which+" GPT reserved header field is nonzero");
        int nents=(int)u32(b,off+80),esize=(int)u32(b,off+84);
        if(nents<=0||esize<=0) throw new IllegalArgumentException(which+" GPT entry dimensions are invalid");
        byte[] guid=new byte[16];System.arraycopy(b,off+56,guid,0,16);
        return new Header(off,size,u32(b,off+8),u64(b,off+24),u64(b,off+32),u64(b,off+40),u64(b,off+48),
                u64(b,off+72),nents,esize,guid);
    }

    private static void checkEntryCrc(byte[] b,int off,int len,long expected,String which) {
        if(crc(b,off,len)!=expected) throw new IllegalArgumentException(which+" GPT entry-array CRC mismatch");
    }
    private static void setHeaderCrc(byte[] b,Header h) { putU32(b,h.off+16,0);putU32(b,h.off+16,crc(b,h.off,h.size)); }
    private static long crc(byte[] b,int off,int len) { CRC32 c=new CRC32();c.update(b,off,len);return c.getValue(); }
    private static long u32(byte[] b,int off) { return ByteBuffer.wrap(b,off,4).order(ByteOrder.LITTLE_ENDIAN).getInt()&0xffffffffL; }
    private static long u64(byte[] b,int off) { long v=ByteBuffer.wrap(b,off,8).order(ByteOrder.LITTLE_ENDIAN).getLong();if(v<0)throw new IllegalArgumentException("GPT sector value exceeds signed 64-bit range");return v; }
    private static void putU32(byte[] b,int off,long v) { ByteBuffer.wrap(b,off,4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)v); }
    private static void putU64(byte[] b,int off,long v) { if(v<0)throw new IllegalArgumentException("negative GPT sector");ByteBuffer.wrap(b,off,8).order(ByteOrder.LITTLE_ENDIAN).putLong(v); }
    private static boolean allZero(byte[] b,int off,int n) { for(int i=0;i<n;i++)if(b[off+i]!=0)return false;return true; }
    private static boolean rangeEquals(byte[] a,int ao,byte[] b,int bo,int n) { for(int i=0;i<n;i++)if(a[ao+i]!=b[bo+i])return false;return true; }
    private static boolean equal(byte[] a,byte[] b) { if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(a[i]!=b[i])return false;return true; }
}
