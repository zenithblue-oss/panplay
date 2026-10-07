package com.winlator.xserver;

import android.os.ParcelFileDescriptor;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadSHMSegment;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class SHMSegmentManager {
    /** Per-client caps so one client cannot pin unbounded mappings. */
    public static final int MAX_SEGMENTS_PER_CLIENT = 64;
    public static final long MAX_BYTES_PER_CLIENT = 512L << 20;

    private final SysVSharedMemory sysVSharedMemory;
    private final SparseArray<ByteBuffer> shmSegments = new SparseArray<>();
    private final SparseBooleanArray fdSegments = new SparseBooleanArray();
    private final SparseArray<XClient> owners = new SparseArray<>();

    public SHMSegmentManager(SysVSharedMemory sysVSharedMemory) {
        this.sysVSharedMemory = sysVSharedMemory;
    }

    // ponytail: O(n) scan over all segments; n is capped at 64 per client.
    private void checkQuota(XClient owner, long size) throws BadAlloc {
        int count = 0;
        long bytes = size;
        for (int i = 0; i < shmSegments.size(); i++) {
            if (owners.get(shmSegments.keyAt(i)) != owner) continue;
            count++;
            bytes += shmSegments.valueAt(i).capacity();
        }
        if (count >= MAX_SEGMENTS_PER_CLIENT || bytes > MAX_BYTES_PER_CLIENT) throw new BadAlloc();
    }

    public void attach(XClient owner, int xid, int shmid) throws XRequestError {
        if (shmSegments.indexOfKey(xid) >= 0) detach(xid);
        checkQuota(owner, 0);
        ByteBuffer data = sysVSharedMemory.attach(shmid);
        if (data == null) return;
        try {
            checkQuota(owner, data.capacity());
        }
        catch (BadAlloc e) {
            sysVSharedMemory.detach(data);
            throw e;
        }
        shmSegments.put(xid, data);
        owners.put(xid, owner);
    }

    /** MIT-SHM 1.2 AttachFd: map a client memfd read-only. Takes ownership of fd (closed after mmap). */
    public void attachFd(XClient owner, int xid, int fd) throws XRequestError {
        if (shmSegments.indexOfKey(xid) >= 0) detach(xid);
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd)) {
            long size = pfd.getStatSize(); // fstat: map no more than the fd really holds (SIGBUS guard)
            if (size <= 0) throw new BadSHMSegment(xid);
            checkQuota(owner, size);
            ByteBuffer data = SysVSharedMemory.mapSHMSegment(fd, size, 0, true);
            if (data == null) throw new BadAlloc();
            shmSegments.put(xid, data);
            fdSegments.put(xid, true);
            owners.put(xid, owner);
        }
        catch (IOException e) {
            throw new BadSHMSegment(xid);
        }
    }

    public void detach(int xid) {
        ByteBuffer data = shmSegments.get(xid);
        if (data != null) {
            if (fdSegments.get(xid, false)) SysVSharedMemory.unmapSHMSegment(data, data.capacity());
            else sysVSharedMemory.detach(data);
            shmSegments.remove(xid);
            fdSegments.delete(xid);
            owners.remove(xid);
        }
    }

    /** Client disconnected: drop every segment it attached but never ShmDetach'd. */
    public void detachAll(XClient owner) {
        for (int i = owners.size() - 1; i >= 0; i--) {
            if (owners.valueAt(i) == owner) detach(owners.keyAt(i));
        }
    }

    public ByteBuffer getData(int xid) {
        return shmSegments.get(xid);
    }
}
