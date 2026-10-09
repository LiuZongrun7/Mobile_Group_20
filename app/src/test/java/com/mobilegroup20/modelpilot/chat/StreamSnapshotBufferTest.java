package com.mobilegroup20.modelpilot.chat;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamSnapshotBufferTest {
    @Test public void manyNetworkChunksScheduleOneCumulativeUiSnapshot() {
        StreamSnapshotBuffer b=new StreamSnapshotBuffer(); assertTrue(b.append("你"));
        assertFalse(b.append("好")); assertFalse(b.append("!")); assertEquals("你好!",b.poll());
        assertNull(b.poll()); assertTrue(b.append(" Again")); assertEquals("你好! Again",b.poll());
    }
    @Test public void alreadyPublishedSnapshotsAreImmutable() {
        StreamSnapshotBuffer b=new StreamSnapshotBuffer(); b.append("A");String a=b.poll();
        b.append("B");assertEquals("A",a);assertEquals("AB",b.poll());
    }
    @Test public void finishingCapturesUnrenderedTailAndDisablesQueuedUiWork() {
        StreamSnapshotBuffer b=new StreamSnapshotBuffer();b.append("Hello");b.append(" world");
        assertEquals("Hello world",b.finish());assertNull(b.poll());assertNull(b.finish());assertFalse(b.append("late"));
    }
    @Test public void cancellationCannotReviveOrPersistTheOldStream() {
        StreamSnapshotBuffer b=new StreamSnapshotBuffer();b.append("Partial");b.discard();
        assertNull(b.poll());assertNull(b.finish());assertFalse(b.append("stale"));
    }
    @Test public void emptyDeltaDoesNotScheduleWork() {
        StreamSnapshotBuffer b=new StreamSnapshotBuffer();assertFalse(b.append(null));assertFalse(b.append(""));
        assertEquals("",b.finish());
    }
    @Test public void concurrentAppendsDoNotLoseCharacters() throws Exception {
        StreamSnapshotBuffer b=new StreamSnapshotBuffer();java.util.List<Thread> threads=new java.util.ArrayList<>();
        for(int i=0;i<8;i++) {
            Thread thread=new Thread(()->{for(int j=0;j<1000;j++) b.append("x");});threads.add(thread);thread.start();
        }
        for(Thread thread:threads) thread.join();assertEquals(8000,b.finish().length());
    }
}
