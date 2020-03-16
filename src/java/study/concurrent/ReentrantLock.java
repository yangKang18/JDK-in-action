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
 * 可重入锁
 */
public class ReentrantLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = 7373984872572414699L;
    /** 同步器 */
    private final Sync sync;

    /**
     * 同步器
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = -5179523762034025860L;

        /**
         * 获取锁
         */
        abstract void lock();

        /**
         * 非公平的尝试获取锁
         */
        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            // 如果锁数量为0，同时当前线程获取锁，设置独占线程
            if (c == 0) {
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            // 如果数量不为0但是线程是独占线程，则累加重入锁数量
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            // 其他表示没有获取锁
            return false;
        }

        /**
         * 尝试释放锁
         */
        protected final boolean tryRelease(int releases) {
            // 减掉释放数量
            int c = getState() - releases;
            // 只有独占锁的线程才能操作，否则报错
            if (Thread.currentThread() != getExclusiveOwnerThread())
                throw new IllegalMonitorStateException();
            boolean free = false;
            // 如果释放后，state为0，则表示锁释放成功，清除独占模式
            if (c == 0) {
                free = true;
                setExclusiveOwnerThread(null);
            }
            setState(c);
            return free;
        }

        /**
         * 是否是独占线程
         */
        protected final boolean isHeldExclusively() {
            // 当前线程是独占线程
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        /**
         * 创建条件队列
         */
        final ConditionObject newCondition() {
            return new ConditionObject();
        }


        /**
         * 获取占用线程，如果没有锁，则返回null
         */
        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        /**
         * 返回重入锁数量
         */
        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        /**
         * 是否有锁
         */
        final boolean isLocked() {
            return getState() != 0;
        }

        /**
         * 序列化
         */
        private void readObject(java.io.ObjectInputStream s)
                throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // reset to unlocked state
        }
    }

    /**
     * 非公平锁，每次先尝试获取锁，不能拿到锁再入队取锁
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;

        /**
         * 非公平锁每次先尝试获取锁，如果获取成功则设置独占线程，尝试获取失败在入队取锁
         */
        final void lock() {
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());
            else
                acquire(1);
        }

        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }

    /**
     * 公平锁，每次都是队列前面的获取锁，新进来的线程只能添加在同步队列后面
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;

        final void lock() {
            acquire(1);
        }

        /**
         * 尝试获取锁
         */
        protected final boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                // 获取锁之前先检查当前线程之前是否有等待的节点，如果有，则获取失败
                if (!hasQueuedPredecessors() &&
                        compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }
    }

    /**
     * 构造函数，默认使用非公平锁
     */
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    /**
     * 构造函数，根据fair指定使用公平锁还是非公平锁
     */
    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

    /**
     * 获取锁，不会报错
     */
    public void lock() {
        sync.lock();
    }

    /**
     * 获取锁，汇报中断异常
     */
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

    /**
     * 尝试获取锁，
     * 获取成功返回true
     * 失败返回false
     */
    public boolean tryLock() {
        return sync.nonfairTryAcquire(1);
    }

    /**
     * 在指定时间内获取锁，超时则获取锁失败
     */
    public boolean tryLock(long timeout, TimeUnit unit)
            throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    /**
     * 释放锁
     */
    public void unlock() {
        sync.release(1);
    }

    /**
     * 新建条件队列
     */
    public Condition newCondition() {
        return sync.newCondition();
    }

    /**
     * 获取锁当前的重入次数
     * 如果不是独占模式，为0
     */
    public int getHoldCount() {
        return sync.getHoldCount();
    }

    /**
     * 是否是线程独占模式
     */
    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * 是否被锁
     */
    public boolean isLocked() {
        return sync.isLocked();
    }

    /**
     * 是否是公平锁
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    /**
     * 获取独占的线程
     */
    protected Thread getOwner() {
        return sync.getOwner();
    }

    /**
     * 是否有入队等待的线程
     */
    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    /**
     * 指定线程是否在同步队列中
     */
    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    /**
     * 获取同步队列中等待节点的数量
     */
    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    /**
     * 获取同步队列中的线程列表
     */
    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    /**
     * 指定条件队列是否有等待节点
     */
    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 指定条件队列中条件等待节点的数量
     */
    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 指定条件队列中等待节点的线程集合
     */
    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * 锁状态字符串化
     */
    public String toString() {
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                "[Unlocked]" :
                "[Locked by thread " + o.getName() + "]");
    }
}
