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
import java.util.Collection;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * 信号量
 * 共享模式下最多允许线程同时执行
 */
public class Semaphore implements java.io.Serializable {
    private static final long serialVersionUID = -3222578661600680210L;
    /** 同步器 */
    private final Sync sync;

    /**
     * 同步器
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 1192457210091910933L;

        /**
         * 构造函数，指定同步器的状态值，也就是信号量允许的数值，但信号量小于等于0时，将阻塞线程
         */
        Sync(int permits) {
            setState(permits);
        }

        /** 获取信号许可数量 */
        final int getPermits() {
            return getState();
        }

        /** 共享模式下非公平获取共享锁 */
        final int nonfairTryAcquireShared(int acquires) {
            for (;;) {
                // 可用数量
                int available = getState();
                // 剩余数量
                int remaining = available - acquires;
                // 如果剩余数量小于0或则CAS更新剩余量成功，返回剩余量
                // 剩余量小于0说明没有获取锁
                // 剩余量更细成功说明成功获取了锁
                if (remaining < 0 ||
                        compareAndSetState(available, remaining))
                    return remaining;
            }
        }

        /**
         * 尝试释放共享锁
         */
        protected final boolean tryReleaseShared(int releases) {
            // 自旋释放锁，一定会操作成功，除非报错
            for (;;) {
                // 当前可用数量
                int current = getState();
                // 释放后数量
                int next = current + releases;
                // 如果释放后数量小于当前量，说明释放数量为负数，报错
                if (next < current)
                    throw new Error("Maximum permit count exceeded");
                // CAS操作成功返回成功
                if (compareAndSetState(current, next))
                    return true;
            }
        }

        /**
         * 减少信号许可数量
         * 此方法并没有去判断减少的信号量与原有信号量的大小，也就是说可能出现原来允许5个，现在减少10个
         * 这样的化，该信号量锁工具将阻塞所有的线程
         */
        final void reducePermits(int reductions) {
            // 自旋减少信号量
            for (;;) {
                // 当前可用量
                int current = getState();
                // 减少后的数量
                int next = current - reductions;
                // 如果减少的信号许可数量小于0，报错
                if (next > current)
                    throw new Error("Permit count underflow");
                // CAS操作成功返回true
                if (compareAndSetState(current, next))
                    return;
            }
        }

        /**
         * 重置信号许可数量，返回重置的数量
         */
        final int drainPermits() {
            for (;;) {
                int current = getState();
                if (current == 0 || compareAndSetState(current, 0))
                    return current;
            }
        }
    }

    /**
     * 非公平锁
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -2694183684443567898L;

        /** 构造函数指定信号许可数量 */
        NonfairSync(int permits) {
            super(permits);
        }

        /**
         * 尝试获取共享锁
         */
        protected int tryAcquireShared(int acquires) {
            return nonfairTryAcquireShared(acquires);
        }
    }

    /**
     * 公平锁
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = 2014338818796000944L;

        /** 构造函数指定信号许可数量 */
        FairSync(int permits) {
            super(permits);
        }

        /**
         * 尝试获取共享锁
         */
        protected int tryAcquireShared(int acquires) {
            for (;;) {
                // 如果当前线程之前有等待的节点，则获取失败
                if (hasQueuedPredecessors())
                    return -1;
                // 没有节点，则尝试获取共享锁
                int available = getState();
                int remaining = available - acquires;
                if (remaining < 0 ||
                        compareAndSetState(available, remaining))
                    return remaining;
            }
        }
    }

    /**
     * 构造函数，默认指定非公平锁
     */
    public Semaphore(int permits) {
        sync = new NonfairSync(permits);
    }

    /**
     * 根据fair指定使用何种锁构造同步器
     */
    public Semaphore(int permits, boolean fair) {
        sync = fair ? new FairSync(permits) : new NonfairSync(permits);
    }

    /**
     * 获取锁，占用1个许可数量
     * 此方法会抛出中断异常
     */
    public void acquire() throws InterruptedException {
        sync.acquireSharedInterruptibly(1);
    }

    /**
     * 获取锁，占用1个许可数量
     * 不会抛出中断异常
     */
    public void acquireUninterruptibly() {
        sync.acquireShared(1);
    }

    /**
     * 尝试获取共享锁
     * 如果获取失败返回false
     */
    public boolean tryAcquire() {
        return sync.nonfairTryAcquireShared(1) >= 0;
    }

    /**
     * 尝试指定超时时间内获取共享锁，占用1个信号许可数量
     * 会抛出中断异常
     */
    public boolean tryAcquire(long timeout, TimeUnit unit)
            throws InterruptedException {
        return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
    }

    /**
     * 释放共享锁
     * 释放1个信号许可数量
     */
    public void release() {
        sync.releaseShared(1);
    }

    /**
     * 获取锁，占用指定许可数量
     * 此方法会抛出中断异常
     */
    public void acquire(int permits) throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireSharedInterruptibly(permits);
    }

    /**
     * 获取锁，占用指定许可数量
     */
    public void acquireUninterruptibly(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.acquireShared(permits);
    }

    /**
     * 尝试获取指定许可数量的共享锁
     */
    public boolean tryAcquire(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        return sync.nonfairTryAcquireShared(permits) >= 0;
    }

    /**
     * 尝试指定超时时间内获取共享锁，占用指定信号许可数量
     * 会抛出中断异常
     */
    public boolean tryAcquire(int permits, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (permits < 0) throw new IllegalArgumentException();
        return sync.tryAcquireSharedNanos(permits, unit.toNanos(timeout));
    }

    /**
     * 释放指定允许数量的共享锁
     */
    public void release(int permits) {
        if (permits < 0) throw new IllegalArgumentException();
        sync.releaseShared(permits);
    }

    /**
     * 获取当前可用许可数量
     */
    public int availablePermits() {
        return sync.getPermits();
    }

    /**
     * 重置所有可用许可数量并返回该数量
     */
    public int drainPermits() {
        return sync.drainPermits();
    }

    /**
     * 减少许可数量
     */
    protected void reducePermits(int reduction) {
        if (reduction < 0) throw new IllegalArgumentException();
        sync.reducePermits(reduction);
    }

    /**
     * 是否是公平锁
     */
    public boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * 是否有等待的线程
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * 同步队列上等待线程的数量
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * 同步队列上等待线程集合
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * 返回许可量
     */
    public String toString() {
        return super.toString() + "[Permits = " + sync.getPermits() + "]";
    }
}
