/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 倒计数同步器
 * 基本只能使用1次
 */
public class CountDownLatch {
    /**
     * 同步器
     */
    private static final class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 4982264981922014374L;

        /** 构造函数，指定计数器数量 */
        Sync(int count) {
            setState(count);
        }

        /** 返回可用数量 */
        int getCount() {
            return getState();
        }

        /**
         * 尝试获取共享锁
         */
        protected int tryAcquireShared(int acquires) {
            return (getState() == 0) ? 1 : -1;
        }

        /**
         * 尝试释放共享锁
         */
        protected boolean tryReleaseShared(int releases) {
            for (;;) {
                int c = getState();
                // 如果剩余量为0，这释放失败
                if (c == 0)
                    return false;
                // 不为0则自减CAS
                int nextc = c-1;
                if (compareAndSetState(c, nextc))
                    return nextc == 0;
            }
        }
    }

    private final Sync sync;

    /**
     * 构造函数指定计数器数量
     */
    public CountDownLatch(int count) {
        if (count < 0) throw new IllegalArgumentException("count < 0");
        this.sync = new Sync(count);
    }

    /**
     * 再计数器数量内则阻塞线程，直到计数器内线程都执行完，执行该方法的线程才会被唤醒
     */
    public void await() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    /**
     * 等待指定时间
     */
    public boolean await(long timeout, TimeUnit unit)
            throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    /**
     * 减少倒计数量，直到是最后一个线程将计数器变为0是，就是释放await的共享锁，只有await的线程就会唤醒继续执行下去
     */
    public void countDown() {
        sync.releaseShared(1);
    }

    /**
     * 返回当前数量
     */
    public long getCount() {
        return sync.getCount();
    }

    public String toString() {
        return super.toString() + "[Count = " + sync.getCount() + "]";
    }
}
