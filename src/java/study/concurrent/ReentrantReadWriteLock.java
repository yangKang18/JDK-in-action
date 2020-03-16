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

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.Collection;

/**
 * 读写锁
 */
public class ReentrantReadWriteLock implements ReadWriteLock, java.io.Serializable {
    private static final long serialVersionUID = -6992448646407690164L;
    /** 读锁 */
    private final ReentrantReadWriteLock.ReadLock readerLock;
    /** 写锁 */
    private final ReentrantReadWriteLock.WriteLock writerLock;
    /** 同步器 */
    final Sync sync;

    /**
     * 构造函数，默认使用非公平锁
     */
    public ReentrantReadWriteLock() {
        this(false);
    }

    /**
     * 指定锁构造函数
     */
    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        // 初始化读写锁
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }

    /** 获取读写锁 */
    public ReentrantReadWriteLock.WriteLock writeLock() { return writerLock; }
    public ReentrantReadWriteLock.ReadLock  readLock()  { return readerLock; }

    /**
     * 同步器
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 6317671515068378041L;

        /**
         * 读写锁的状态值规则
         * 对于state字段，高16为表示读锁数量，低16位标识写锁数量
         */
        static final int SHARED_SHIFT   = 16;
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        /**
         * 独占遮罩：00000000000000001111111111111111
         * 在&运算中，低16位表示的是写锁数量
         */
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /**
         * 共享读锁数量值，状态值右移16位，高位补0
         */
        static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
        /**
         * 独占写锁数量值
         */
        static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }

        /**
         * 线程持有读锁的数量
         */
        static final class HoldCounter {
            int count = 0;
            /**
             * 线程标志号ID
             */
            final long tid = getThreadId(Thread.currentThread());
        }

        /**
         * 线程缓存，存储线程持有锁数量
         */
        static final class ThreadLocalHoldCounter
                extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                // 默认返回一个持有对象
                return new HoldCounter();
            }
        }

        /** 本地线程变量 */
        private transient ThreadLocalHoldCounter readHolds;
        /** 缓存的线程持有数量 */
        private transient HoldCounter cachedHoldCounter;
        /** 第一个读线程 */
        private transient Thread firstReader = null;
        /** 第一个读线程的持有数量 */
        private transient int firstReaderHoldCount;

        /**
         * 构造函数
         */
        Sync() {
            // 初始化本地线程变量缓存
            readHolds = new ThreadLocalHoldCounter();
            // 设置状态值
            setState(getState());
        }

        /**
         * Returns true if the current thread, when trying to acquire
         * the read lock, and otherwise eligible to do so, should block
         * because of policy for overtaking other waiting threads.
         */
        abstract boolean readerShouldBlock();

        /**
         * Returns true if the current thread, when trying to acquire
         * the write lock, and otherwise eligible to do so, should block
         * because of policy for overtaking other waiting threads.
         */
        abstract boolean writerShouldBlock();

        /**
         * 尝试释放锁
         */
        protected final boolean tryRelease(int releases) {
            // 写锁的释放一定是独占的，不然报错
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int nextc = getState() - releases;
            boolean free = exclusiveCount(nextc) == 0;
            // 如果写锁释放为0，表示写锁释放完毕，清除独占线程
            if (free)
                setExclusiveOwnerThread(null);
            setState(nextc);
            return free;
        }

        /**
         * 尝试获取锁
         */
        protected final boolean tryAcquire(int acquires) {
            Thread current = Thread.currentThread();
            int c = getState();
            int w = exclusiveCount(c);
            // 状态值不为0，说明有锁，至于是读锁还是写锁需要分析
            if (c != 0) {
                // (Note: if c != 0 and w == 0 then shared count != 0)
                // 如果写锁没有或者写锁不是当前线程，返回取锁失败
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                // 如果写锁超过最大数量，报错
                if (w + exclusiveCount(acquires) > MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // Reentrant acquire
                // 重入增加锁数量
                setState(c + acquires);
                return true;
            }
            // 为0说明没有锁，写锁根据锁分类，
            // 如果是非公平锁，如果获取锁CAS成功则设置独占线程，
            // 如果是公平锁，需要队列是否有等待节点，有则取锁失败
            if (writerShouldBlock() ||
                    !compareAndSetState(c, c + acquires))
                return false;
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * 释放共享锁
         */
        protected final boolean tryReleaseShared(int unused) {
            // 自减线程是有数量
            Thread current = Thread.currentThread();
            if (firstReader == current) {
                // assert firstReaderHoldCount > 0;
                // 如果第一个读锁线程持有量为1，释放后，该线程就没有锁了，所以第一个读锁线程可以清空了
                if (firstReaderHoldCount == 1)
                    firstReader = null;
                else
                    firstReaderHoldCount--;
            } else {
                // 如果不是本线程，本地线程缓存获取缓存计数器
                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != getThreadId(current))
                    rh = readHolds.get();
                int count = rh.count;
                // 如果缓存计数器中数量为1，则移除该缓存
                if (count <= 1) {
                    readHolds.remove();
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                // 自减持有数量
                --rh.count;
            }

            // 自旋释放锁，即自减状态值
            for (;;) {
                int c = getState();
                int nextc = c - SHARED_UNIT;
                if (compareAndSetState(c, nextc))
                    // 只有当state为0时才表示读锁释放完毕
                    // 但是读锁数量本身没有什么影响
                    return nextc == 0;
            }
        }

        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                    "attempt to unlock read lock, not locked by current thread");
        }

        /**
         * 尝试获取共享锁
         */
        protected final int tryAcquireShared(int unused) {
            Thread current = Thread.currentThread();
            int c = getState();
            // 如果持有写锁且独占线程不是当前线程，则获取读锁失败
            if (exclusiveCount(c) != 0 &&
                    getExclusiveOwnerThread() != current)
                return -1;
            // 没有写锁时，读锁不阻塞并且读锁数量不超过最大值并且设置读锁数量成功
            int r = sharedCount(c);
            if (!readerShouldBlock() &&
                    r < MAX_COUNT &&
                    compareAndSetState(c, c + SHARED_UNIT)) {
                // 如果时第一个读锁
                if (r == 0) {
                    // 设置读锁线程为当前线程
                    firstReader = current;
                    // 设置读锁线程的的数量为1
                    firstReaderHoldCount = 1;
                } else if (firstReader == current) {
                    // 如果已有读锁线程且读锁线程为当前线程，则自增持有数量
                    firstReaderHoldCount++;
                } else {
                    // 如果不是当前线程
                    HoldCounter rh = cachedHoldCounter;
                    // 获取缓存计数器，如果缓存计数器为空或者线程ID不是当前线程ID，则生成一个新的线程ID存放在线程变量中，
                    if (rh == null || rh.tid != getThreadId(current))
                        cachedHoldCounter = rh = readHolds.get();
                    else if (rh.count == 0)
                        // 如果有缓存计数器，且计数器数量为0，则在线程变量中添加该缓存
                        readHolds.set(rh);
                    // 自增线程持有数量
                    rh.count++;
                }
                return 1;
            }
            // 在CAS操作失败时，自旋操作获取锁操作
            return fullTryAcquireShared(current);
        }

        /**
         * 自旋获取读锁
         */
        final int fullTryAcquireShared(Thread current) {
            HoldCounter rh = null;
            for (;;) {
                int c = getState();
                // 如果持有写锁且独占线程不是当前线程，则获取读锁失败
                if (exclusiveCount(c) != 0) {
                    if (getExclusiveOwnerThread() != current)
                        return -1;
                    // 如果读锁被阻塞
                } else if (readerShouldBlock()) {
                    if (firstReader == current) {
                        // assert firstReaderHoldCount > 0;
                    } else {
                        // 如果当前线程不是第一个读锁线程，从线程变量中移除缓存，因为该读锁获取失败
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) {
                                rh = readHolds.get();
                                if (rh.count == 0)
                                    readHolds.remove();
                            }
                        }
                        if (rh.count == 0)
                            return -1;
                    }
                }
                // 超过最大读锁数量，报错
                if (sharedCount(c) == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // 设置读锁成功后，更新读锁线程持有的缓存
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (sharedCount(c) == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    return 1;
                }
            }
        }

        /**
         * 尝试获取写锁
         * 最大的区别在于是否需要判断写锁阻塞条件，此方法不需要判断，和非公平锁类似
         */
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if (c != 0) {
                int w = exclusiveCount(c);
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                if (w == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
            }
            if (!compareAndSetState(c, c + 1))
                return false;
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * 尝试获取读锁
         * 只要没有写锁独占，只要不超过读锁最大数量，读锁一定获取成功
         */
        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            // 自旋保证拿锁成功
            for (;;) {
                int c = getState();
                if (exclusiveCount(c) != 0 &&
                        getExclusiveOwnerThread() != current)
                    return false;
                int r = sharedCount(c);
                if (r == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (r == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            cachedHoldCounter = rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
            }
        }

        /**
         * 当前线程是否是独占线程
         */
        protected final boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        /**
         * 获取条件队列
         */
        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        /**
         * 获取当前持有的线程
         */
        final Thread getOwner() {
            // Must read state before owner to ensure memory consistency
            return ((exclusiveCount(getState()) == 0) ?
                    null :
                    getExclusiveOwnerThread());
        }

        /**
         * 获取读锁数量
         */
        final int getReadLockCount() {
            return sharedCount(getState());
        }

        /**
         * 是否有读锁
         */
        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        /**
         * 获取写锁数量
         */
        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        /**
         * 获取当前线程读锁持有数量
         */
        final int getReadHoldCount() {
            if (getReadLockCount() == 0)
                return 0;

            Thread current = Thread.currentThread();
            if (firstReader == current)
                return firstReaderHoldCount;

            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return rh.count;

            int count = readHolds.get().count;
            if (count == 0) readHolds.remove();
            return count;
        }

        private void readObject(java.io.ObjectInputStream s)
                throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            readHolds = new ThreadLocalHoldCounter();
            setState(0); // reset to unlocked state
        }

        final int getCount() { return getState(); }
    }

    /**
     * 非公平锁
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;
        final boolean writerShouldBlock() {
            // 非公平锁有一次抢占机会，所有可以默认不阻塞的，先抢占一下看是否能获取锁
            return false;
        }
        final boolean readerShouldBlock() {
            // 检查第一个节点是不是独占线程，如果是独占线程则阻塞读锁
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    /**
     * 公平锁
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;

        final boolean writerShouldBlock() {
            // 只要同步队列有等待节点就需要阻塞
            return hasQueuedPredecessors();
        }
        final boolean readerShouldBlock() {
            // 只要同步队列有等待节点就需要阻塞
            return hasQueuedPredecessors();
        }
    }

    /**
     * 读锁
     */
    public static class ReadLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -5992448646407690164L;
        /** 同步器 */
        private final Sync sync;

        /**
         * 构造函数
         */
        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * 获取读锁
         */
        public void lock() {
            sync.acquireShared(1);
        }

        /**
         * 获取读锁
         * 报中断异常
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        /**
         * 尝试获取读锁
         */
        public boolean tryLock() {
            return sync.tryReadLock();
        }

        /**
         * 尝试在指定时间内获取读锁
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        /**
         * 释放读锁
         */
        public void unlock() {
            sync.releaseShared(1);
        }

        /**
         * 读锁不支持条件队列，读锁是共享的，只有独占模式下才支持条件对了
         */
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public String toString() {
            // 获取读锁数量
            int r = sync.getReadLockCount();
            return super.toString() + "[Read locks = " + r + "]";
        }
    }

    /**
     * 写锁
     */
    public static class WriteLock implements Lock, java.io.Serializable {
        private static final long serialVersionUID = -4992448646407690164L;
        private final Sync sync;

        /**
         * 构造函数
         */
        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        /**
         * 获取独占锁
         */
        public void lock() {
            sync.acquire(1);
        }

        /**
         * 获取写锁
         * 会报中断异常
         */
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        /**
         * 尝试获取写锁
         */
        public boolean tryLock( ) {
            return sync.tryWriteLock();
        }

        /**
         * 尝试在指定时间内获取写锁
         */
        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        /**
         * 释放写锁
         */
        public void unlock() {
            sync.release(1);
        }

        /**
         * 写锁条件队列
         */
        public Condition newCondition() {
            return sync.newCondition();
        }

        public String toString() {
            // 写锁独占线程
            Thread o = sync.getOwner();
            return super.toString() + ((o == null) ? "[Unlocked]" : "[Locked by thread " + o.getName() + "]");
        }

        /**
         * 当前线程是否是独占线程
         */
        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        /**
         * 获取写锁数量
         */
        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }


    /**
     * 是否使用公平锁
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * 返回独占线程
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * 返回读锁数量
     */
    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    /**
     * 是否写锁独占
     */
    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    /**
     * 是否当前线程是写锁独占
     */
    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * 获取写锁数量
     */
    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    /**
     * 获取读锁支持有数量
     */
    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    /**
     * 获取写锁等待线程集合
     */
    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    /**
     * 获取读锁等来线程
     */
    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

    /**
     * 同步队列是否有等待线程
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * 指定线程是否在同步队列上
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * 同步队列长度
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * 同步队列上等待的线程集合
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * 条件队列是否有等待节点
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 条件队列的等待节点数量
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 条件队列上等待节点的线程集合
     */
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);

        return super.toString() +  "[Write locks = " + w + ", Read locks = " + r + "]";
    }

    /**
     * 返回线程标记ID
     */
    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long TID_OFFSET;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            TID_OFFSET = UNSAFE.objectFieldOffset
                    (tk.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
