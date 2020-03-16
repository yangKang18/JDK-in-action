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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import sun.misc.Unsafe;

/**
 * 队列同步器
 */
public abstract class AbstractQueuedSynchronizer
        extends AbstractOwnableSynchronizer
        implements java.io.Serializable {

    private static final long serialVersionUID = 7373984972572414691L;

    /**
     * 空构造
     */
    protected AbstractQueuedSynchronizer() { }

    /**
     * 等待锁的线程节点
     */
    static final class Node {
        /** 共享模式下等待节点标记 */
        static final Node SHARED = new Node();
        /** 独占模式下等待节点标识 */
        static final Node EXCLUSIVE = null;

        /** 节点状态：该线程节点已取消 */
        static final int CANCELLED =  1;
        /** 节点状态：该线程节点等待被唤醒 */
        static final int SIGNAL    = -1;
        /** 节点状态：该线程节点等待被条件唤醒 */
        static final int CONDITION = -2;
        /** 节点状态：线程节点传播 */
        static final int PROPAGATE = -3;

        volatile int waitStatus;

        /** 线程等待节点的前驱节点 */
        volatile Node prev;

        /** 线程等待节点的后驱节点 */
        volatile Node next;

        /** 当前节点的线程 */
        volatile Thread thread;

        /** 条件队列中的后驱节点 */
        Node nextWaiter;

        /**
         * 判断该节点是否是共享模式：即条件队列中时shared节点
         */
        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * 返回该节点的前驱节点
         * 设计中，等待的节点一定有前驱节点，首节点为当前有锁的空节点，所有等待的节点在该节点之后
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {
        }

        /**
         * 添加线程等待节点，
         * mode如果是EXCLUSIVE，则标识独占模式下的等待节点
         * mode如果是SHARED，则标识共享模式下的等待节点
         */
        Node(Thread thread, Node mode) {
            this.nextWaiter = mode;
            this.thread = thread;
        }

        /**
         * 添加线程等待节点，等待状态，此为条件队列中添加的节点
         */
        Node(Thread thread, int waitStatus) {
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    /**
     * 等待队列的首节点
     */
    private transient volatile Node head;

    /**
     * 等待队列的尾节点
     */
    private transient volatile Node tail;

    /**
     * 同步器的状态值
     * 主要标记持有锁的状态
     */
    private volatile int state;

    /**
     * 获取状态值
     */
    protected final int getState() {
        return state;
    }

    /**
     * 设置状态值
     */
    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * 设置状态值，此为CAS操作，操作失败返回false
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    // Queuing utilities

    /**
     * 自旋超时时间，纳秒
     */
    static final long spinForTimeoutThreshold = 1000L;

    /**
     * 入队操作，返回前驱节点
     */
    private Node enq(final Node node) {
        // 自旋操作
        for (;;) {
            Node t = tail;
            // 如果尾节点不存在，即当前没有等待节点链表，则新建一个节点作为头尾节点
            if (t == null) {
                if (compareAndSetHead(new Node()))
                    tail = head;
            } else {
                // 当前节点的前驱指向该尾节点，CAS成功，则原尾节点的后驱指向当前节点，返回前驱节点
                node.prev = t;
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * 创建一个等待节点并入队
     */
    private Node addWaiter(Node mode) {
        // 创建节点
        // mode指定模式，一般是独占和共享
        Node node = new Node(Thread.currentThread(), mode);
        // Try the fast path of enq; backup to full enq on failure
        // 从尾节点添加
        // 此处创建一个新引用指向tail指向的节点，主要是在并发过程中，tail是动态变化的
        // 在插入节点时，总是考虑插入之前的尾节点应该是明确的，即使之后tail指向的节点发生变化，在后面的CAS操作中，
        // 期望的尾节点发生变化，更新尾节点绝对不会成功，还是会采用自旋添加尾节点的方式实现
        // 但是此时当前节点的前驱还是指向在此时的pred上，这个会在enq操作中更新
        Node pred = tail;
        if (pred != null) {
            // 如果尾节点不为空，则当前节点的前驱指针指向尾节点，CAS更新尾节点指针，如果成功，则前驱节点的后驱指针指向当前节点
            // 返回当前创建的节点
            node.prev = pred;
            if (compareAndSetTail(pred, node)) {
                pred.next = node;
                return node;
            }
        }
        // 如果尾节点不存在或者CAS操作失败，则自旋入队
        enq(node);
        return node;
    }

    /**
     * 设置头节点
     */
    private void setHead(Node node) {
        // 头节点指向指定节点
        // 该指定节点的线程和前驱都清空
        head = node;
        node.thread = null;
        node.prev = null;
        // 但是原头节点后驱指向的当前节点的引用并没有清除
        // 也就是node.prev.next还连着
    }

    /**
     * 唤醒该节点的继承者节点
     */
    private void unparkSuccessor(Node node) {
        // 如果该节点等待被唤醒，则设置他为0
        int ws = node.waitStatus;
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);

        // 获取该节点的后驱节点，如果该节点不为空也不是取消的节点，就唤醒该节点的线程，否则就从当前的尾节点往前找
        Node s = node.next;
        if (s == null || s.waitStatus > 0) {
            s = null;
            // 从尾节点开始往前找，找到不是自己节点且不是取消的节点为止
            // 移除取消节点是，都是指定了后驱节点，但是前驱指针是没有发生变化的，如果从前往后找，后驱指针可能会中断了
            // 从尾巴往头的寻找则一定成功，即使被移除的节点，前驱节点的指向也是没有清除的
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        if (s != null)
            LockSupport.unpark(s.thread);
    }

    /**
     * 共享模式下释放锁操作
     */
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        for (;;) {
            Node h = head;
            // 当前节点不是头节点也不是尾节点时
            if (h != null && h != tail) {
                // 获取当前等待状态值，如果是被唤醒的状态，改为0，并唤醒后继等待节点
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;            // loop to recheck cases
                    unparkSuccessor(h);
                }
                // 如果等待状态为0，改为传播标记
                else if (ws == 0 &&
                        !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
            }
            // 如果head没发生变化则结束
            if (h == head)                   // loop if head changed
                break;
        }
    }

    /**
     * 共享模式下设置头和共享的传播值
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        // 设置头节点
        Node h = head;
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        // 如果传播值大于0，或者原头节点为空，或者原头节点的等待状态小于0，
        // 如果当前节点的后驱不会空或者是共享节点的化，释放共享锁
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
                (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared())
                // 释放共享锁
                doReleaseShared();
        }
    }


    /**
     * 移除获取锁失败的节点
     */
    private void cancelAcquire(Node node) {
        // 节点为空，不处理
        if (node == null)
            return;

        // 移除当前节点的线程以用
        node.thread = null;
        // 检查节点的前驱节点是否取消，有取消的，一并清除
        Node pred = node.prev;
        while (pred.waitStatus > 0)
            // 只要前驱节点的状态为已取消，移除节点的前驱就指向前驱的前驱
            node.prev = pred = pred.prev;

        // 获取前驱节点的后驱指针
        Node predNext = pred.next;

        // 设置当前节点的状态为取消
        node.waitStatus = Node.CANCELLED;

        // 如果当前节点为尾节点，移除该节点成功后，当前节点的前驱变成了tail
        if (node == tail && compareAndSetTail(node, pred)) {
            // 前驱变为尾节点后，其后驱就指向null
            compareAndSetNext(pred, predNext, null);
        } else {
            // 如果要移除的节点不是尾节点
            int ws;
            // 前驱不是头节点，并且前驱线程不为空，并且前驱为唤醒状态时
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {
                // 如果当前节点的后驱不为空且也不是取消的节点，则前驱节点的后驱指针指向当前节点的后驱
                Node next = node.next;
                if (next != null && next.waitStatus <= 0) {
                    compareAndSetNext(pred, predNext, next);
                }
            } else {
                // 经过前面对取消节点的清除，只要不是头节点，都会是被唤醒的状态的，所以能走到这里，说明该节点是头节点的下一个节点
                // 所以可以直接唤醒该节点的继承者节点
                unparkSuccessor(node);
            }
            // 当前节点的后驱指向自己，但是前驱还是指向前驱节点上
            node.next = node;
        }
    }

    /**
     * 获取锁失败后检查阻塞
     * true：阻塞
     * false：不阻塞
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        // 获取前驱节点的等待状态
        int ws = pred.waitStatus;
        // 如果前驱节点的状态为等待被唤醒，则返回true
        if (ws == Node.SIGNAL)
            return true;
        // 如果前驱节点大于0即截取节点被取消，则移除该节点
        if (ws > 0) {
            do {
                // 只要前驱节点是取消的，当前节点的前驱指向前驱的前驱，
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            // 前驱节点的后驱指针指向当前节点
            pred.next = node;
        } else {
            // 把前驱节点的等待状态改为唤醒
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * Convenience method to interrupt current thread.
     */
    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * 阻塞线程并返回中断状态
     */
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        return Thread.interrupted();
    }

    /**
     * 在队列中自旋取锁
     */
    final boolean acquireQueued(final Node node, int arg) {
        // 节点等待取锁失败标记
        boolean failed = true;
        try {
            // 节点等待线程中断标记
            boolean interrupted = false;
            // 自旋操作
            for (;;) {
                // 获取当前节点的前驱节点，如果前驱节点是头空节点，则在再次尝试获取锁
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    // 取锁成功，则当前节点设置为头节点，且头节点的需要置空
                    setHead(node);
                    // 头节点不再链接后驱节点，
                    // 此时p不再链接任何节点，便于GC，head指向node，node前驱和线程清空，剩后驱节点指向next
                    p.next = null;
                    failed = false;
                    return interrupted;
                }
                // 不是头节点或者尝试取锁失败后，检查阻塞并检查中断
                // 检查阻塞时主要移除取消的节点，更改前节点为唤醒状态
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            // 节点取锁失败，则移除该节点
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 独占模式下获取锁，会报错
     */
    private void doAcquireInterruptibly(int arg)
            throws InterruptedException {
        // 新建等待节点，并入队
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            // 自旋取锁，此处逻辑和acquireQueued方法大同小异，唯一差异在于中断会抛异常出来
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null;
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 独占模式下，指定等待时间获取锁
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // 时间判断，小于0 ，获取失败
        if (nanosTimeout <= 0L)
            return false;
        // 截止时间
        final long deadline = System.nanoTime() + nanosTimeout;
        // 添加等待节点
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            // 自旋取锁或等待
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                // 检查截止时间完结，没有获得锁返回失败
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                // 检查阻塞条件
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                // 检查中断
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            // 如果失败则移除等待节点
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 共享模式获取锁
     */
    private void doAcquireShared(int arg) {
        // 新增共享模式等待节点并入队
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            // 自旋获取锁
            for (;;) {
                // 前驱节点如果为头节点，再次尝试获取共享锁
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        // 如果获取锁成功，设置头节点和传播值
                        setHeadAndPropagate(node, r);
                        // 原头节点的后驱清空
                        p.next = null;
                        // 如果线程中断则中断自己
                        if (interrupted)
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                // 检查阻塞条件和阻塞
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            // 取锁失败，则移除等待节点
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 入队取锁，
     * 和doAcquireShared类似，只是会抛出中断异常
     */
    private void doAcquireSharedInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null;
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * 等待指定纳秒取锁
     */
    private boolean doAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        // 截止时间
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        failed = false;
                        return true;
                    }
                }
                // 检查截止时间
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    // Main exported methods

    /**
     * Attempts to acquire in exclusive mode. This method should query
     * if the state of the object permits it to be acquired in the
     * exclusive mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread. This can be used
     * to implement method {@link Lock#tryLock()}.
     *
     * <p>The default
     * implementation throws {@link UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return {@code true} if successful. Upon success, this object has
     *         been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in exclusive
     * mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this object is now in a fully released
     *         state, so that any waiting threads may attempt to acquire;
     *         and {@code false} otherwise.
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if exclusive mode is not supported
     */
    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to set the state to reflect a release in shared mode.
     *
     * <p>This method is always invoked by the thread performing release.
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException}.
     *
     * @param arg the release argument. This value is always the one
     *        passed to a release method, or the current state value upon
     *        entry to a condition wait.  The value is otherwise
     *        uninterpreted and can represent anything you like.
     * @return {@code true} if this release of shared mode may permit a
     *         waiting acquire (shared or exclusive) to succeed; and
     *         {@code false} otherwise
     * @throws IllegalMonitorStateException if releasing would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns {@code true} if synchronization is held exclusively with
     * respect to the current (calling) thread.  This method is invoked
     * upon each call to a non-waiting {@link ConditionObject} method.
     * (Waiting methods instead invoke {@link #release}.)
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}. This method is invoked
     * internally only within {@link ConditionObject} methods, so need
     * not be defined if conditions are not used.
     *
     * @return {@code true} if synchronization is held exclusively;
     *         {@code false} otherwise
     * @throws UnsupportedOperationException if conditions are not supported
     */
    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    /**
     * 独占模式下的获取锁
     */
    public final void acquire(int arg) {
        // 1. 先尝试获取锁 -> 由子类实现尝试获取锁的具体规则
        // 2. 获取锁失败则添加独占模式的等待节点
        // 3. 入队等待获取锁
        // 4. 如果等待过程中中断，则执行中断操作
        if (!tryAcquire(arg) &&
                acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    /**
     * 独占模式下获取锁，或抛出中断异常
     */
    public final void acquireInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        // 尝试获取锁，获取失败则获取锁，或报错
        if (!tryAcquire(arg))
            doAcquireInterruptibly(arg);
    }

    /**
     * 独占模式下尝试获取锁，在指定纳秒时间内
     * 报中断错误
     */
    public final boolean tryAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        // 检查中断
        if (Thread.interrupted())
            throw new InterruptedException();
        // 尝试获取锁，如果获取失败，等待指定时间获取锁
        return tryAcquire(arg) ||
                doAcquireNanos(arg, nanosTimeout);
    }

    /**
     * 独占模式下释放锁
     */
    public final boolean release(int arg) {
        // 尝试释放锁
        if (tryRelease(arg)) {
            // 成功则唤醒后继节点
            Node h = head;
            if (h != null && h.waitStatus != 0)
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * 共享模式下获取锁
     */
    public final void acquireShared(int arg) {
        // 尝试获取共享锁，为获取，则获取共享锁
        if (tryAcquireShared(arg) < 0)
            doAcquireShared(arg);
    }

    /**
     * 共享模式下获取锁，会抛出中断异常
     */
    public final void acquireSharedInterruptibly(int arg)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        // 尝试获取锁，没有获取锁则入队获取锁
        if (tryAcquireShared(arg) < 0)
            doAcquireSharedInterruptibly(arg);
    }

    /**
     * 共享模式下获取锁，等待指定时间获取锁,会抛出中断异常
     */
    public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted())
            throw new InterruptedException();
        // 尝试获取锁，没有获取锁则等待指定时间获取锁
        return tryAcquireShared(arg) >= 0 ||
                doAcquireSharedNanos(arg, nanosTimeout);
    }

    /**
     * 释放共享锁
     */
    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {
            doReleaseShared();
            return true;
        }
        return false;
    }

    /**
     * 是否有队列中的等待线程
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * 是否有等待，即只要队列初始化过就为true
     */
    public final boolean hasContended() {
        return head != null;
    }

    /**
     * 获取队列中第一个等待节点的线程
     */
    public final Thread getFirstQueuedThread() {
        // 如果队列没数据，则返回null，否则全查获取
        return (head == tail) ? null : fullGetFirstQueuedThread();
    }

    /**
     * 获取队列第一个等待节点的线程
     */
    private Thread fullGetFirstQueuedThread() {
        Node h, s;
        Thread st;
        // 如果头节点不为空，且头节点有后驱节点不为空，并且后驱节点为头节点，并且后驱节点的线程引用不为空
        // 此处两次计算，应该是防止head没初始化或者被重新设置了head，在两次读取中前后比较不一致
        if (((h = head) != null && (s = h.next) != null &&
                s.prev == head && (st = s.thread) != null) ||
                ((h = head) != null && (s = h.next) != null &&
                        s.prev == head && (st = s.thread) != null))
            return st;

        // 如果head没有设置或者在两次读取中变更了head，那么就从尾巴往前找，找到为头节点为止
        Node t = tail;
        Thread firstThread = null;
        // 从尾节点往头找
        while (t != null && t != head) {
            Thread tt = t.thread;
            // 当前节点线程不为空则取该节点
            if (tt != null)
                firstThread = tt;
            // 遍历节点往前驱移动
            t = t.prev;
        }
        return firstThread;
    }

    /**
     * 查询指定线程是否在等待队列
     */
    public final boolean isQueued(Thread thread) {
        if (thread == null)
            throw new NullPointerException();
        // 从尾巴往前找，如果等待节点有线程，返回true
        for (Node p = tail; p != null; p = p.prev)
            if (p.thread == thread)
                return true;
        return false;
    }

    /**
     * 队列第一个等待线程的节点是不是独占模式
     */
    final boolean apparentlyFirstQueuedIsExclusive() {
        Node h, s;
        // 头节点不为空，且头节点的后驱不为空，且后驱节点不是共享节点，且后驱节点的线程存在
        return (h = head) != null &&
                (s = h.next)  != null &&
                !s.isShared()         &&
                s.thread != null;
    }

    /**
     * 队列是否还有比当前线程还久远的线程在等待
     */
    public final boolean hasQueuedPredecessors() {
        Node t = tail;
        Node h = head;
        Node s;
        // 首尾节点不能指向同一个，并且后驱节点为空或者节点线程不是当前线程
        return h != t &&
                ((s = h.next) == null || s.thread != Thread.currentThread());
    }


    /**
     * 等待队列的数量
     */
    public final int getQueueLength() {
        int n = 0;
        for (Node p = tail; p != null; p = p.prev) {
            if (p.thread != null)
                ++n;
        }
        return n;
    }

    /**
     * 等待队列中的线程集合
     */
    public final Collection<Thread> getQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            Thread t = p.thread;
            if (t != null)
                list.add(t);
        }
        return list;
    }

    /**
     * 等待队列中独占模式的线程集合
     */
    public final Collection<Thread> getExclusiveQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (!p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * 等待队列中共享模式的线程集合
     */
    public final Collection<Thread> getSharedQueuedThreads() {
        ArrayList<Thread> list = new ArrayList<Thread>();
        for (Node p = tail; p != null; p = p.prev) {
            if (p.isShared()) {
                Thread t = p.thread;
                if (t != null)
                    list.add(t);
            }
        }
        return list;
    }

    /**
     * 字符串化
     */
    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
                "[State = " + s + ", " + q + "empty queue]";
    }


    /**
     * 检查节点是否在同步队列中
     */
    final boolean isOnSyncQueue(Node node) {
        // 如果该节点为条件状态或者前驱为空，表示该节点还在条件队列中，不在同步队列上
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        // 如果该节点有后驱，则该节点一定在同步队列上
        if (node.next != null)
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        // 由于在同步队列上添加节点时，先设置的前驱再进行的CAS操作，如果cas失败或者再执行过程中，该节点时有前驱的
        // 所有为了安全期间，再次从同步队列的尾部开始查找
        return findNodeFromTail(node);
    }

    /**
     * 从同步队列的尾部开始查找
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * 转移该节点到同步队列
     */
    final boolean transferForSignal(Node node) {
        /*
         * If cannot change waitStatus, the node has been cancelled.
         */
        // CAS更改节点等待状态，变更失败返回转移失败
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        // 同步队列入队
        Node p = enq(node);
        // 如果该节点是取消的，或者更改前驱节点状态为唤醒状态失败，则唤醒该节点
        int ws = p.waitStatus;
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);
        return true;
    }

    /**
     * 取消等待后，该节点添加到同步队列上
     */
    final boolean transferAfterCancelledWait(Node node) {
        // 更改等待节点状态成功后就入队
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        // 如果该节点不在队列上，该线程一致让步资源，直到该节点在队列上为止
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
    }

    /**
     * 全部释放锁
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            // 获取现有的锁数量
            int savedState = getState();
            // 释放所有锁，释放失败报错，其实就是把state值释放为0
            if (release(savedState)) {
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            // 如果释放失败，该节点置为取消节点
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    // Instrumentation methods for conditions

    /**
     * 条件的创建对象是否是自身
     */
    public final boolean owns(ConditionObject condition) {
        return condition.isOwnedBy(this);
    }

    /**
     * 条件队列是否有条件等待节点
     */
    public final boolean hasWaiters(ConditionObject condition) {
        // 只有自身构建的condition才可能查看
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.hasWaiters();
    }

    /**
     * 条件队列中等待节点的数量
     */
    public final int getWaitQueueLength(ConditionObject condition) {
        // 只有自身构建的condition才可能查看
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitQueueLength();
    }

    /**
     * 条件队列中等待节点的线程集合
     */
    public final Collection<Thread> getWaitingThreads(ConditionObject condition) {
        // 只有自身构建的condition才可能查看
        if (!owns(condition))
            throw new IllegalArgumentException("Not owner");
        return condition.getWaitingThreads();
    }

    /**
     * 条件队列
     */
    public class ConditionObject implements Condition, java.io.Serializable {
        private static final long serialVersionUID = 1173984872572414699L;
        /** 条件队列首节点 */
        private transient Node firstWaiter;
        /** 条件队列尾节点 */
        private transient Node lastWaiter;

        /**
         * 空构造函数
         */
        public ConditionObject() { }

        /**
         * 条件队列上添加一个条件等待节点
         */
        private Node addConditionWaiter() {
            // 取尾节点，如果尾节点不为空，且不再是条件状态，则移除取消条件的等待节点
            Node t = lastWaiter;
            // If lastWaiter is cancelled, clean out.
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            // 新建节点
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            // 如果尾节点为空，说明未初始化等待节点链表，该节点为头节点
            if (t == null)
                firstWaiter = node;
            else
                // 否则尾节点的后驱指向该节点
                t.nextWaiter = node;
            // 尾节点指向该节点
            lastWaiter = node;
            return node;
        }

        /**
         * 唤醒节点
         * 其实就是移除条件队列的节点，添加到同步节点上
         */
        private void doSignal(Node first) {
            // 只要迁移到同步队列失败并且头节点不为空
            // 就设置该节点的下一节点为空，头节点指向当前节点的后驱节点，如果后驱为空，则首尾节点都为空
            do {
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                first.nextWaiter = null;
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
        }

        /**
         * 唤醒所有节点
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
            // 此处和唤醒一个节点有点区别，在于transferForSignal唤醒可能失败，在唤醒所有节点中，如果有失败的，则也会唤醒下一个
        }

        /**
         * 不再等待的节点从条件队列上移除
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            Node trail = null;
            // 头节点不为空
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {
                    // 如果头节点不是等待节点，清除头节点的后驱节点
                    t.nextWaiter = null;
                    // 如果临时节点未空，则头节点指针后移，否者临时节点的后驱指向后驱节点
                    if (trail == null)
                        firstWaiter = next;
                    else
                        trail.nextWaiter = next;
                    // 如果后驱节点不存在，则尾节点指想trail
                    if (next == null)
                        lastWaiter = trail;
                }
                else
                    trail = t;
                t = next;
            }
        }

        // public methods

        /**
         * 第一个节点开始，唤醒一个节点为止
         */
        public final void signal() {
            // 不是独占模式报错
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // 头节点不为空，即有等待节点才可以唤醒操作
            Node first = firstWaiter;
            if (first != null)
                doSignal(first);
        }

        /**
         * 唤醒所有节点
         */
        public final void signalAll() {
            // 不是独占模式报错
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * 不间断等待
         */
        public final void awaitUninterruptibly() {
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean interrupted = false;
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if (Thread.interrupted())
                    interrupted = true;
            }
            if (acquireQueued(node, savedState) || interrupted)
                selfInterrupt();
        }

        /** 自我中断模式 */
        private static final int REINTERRUPT =  1;
        /** 报错模式 */
        private static final int THROW_IE    = -1;

        /**
         * 等待条件唤醒时检查中断
         */
        private int checkInterruptWhileWaiting(Node node) {
            // 未中断返回0，
            return Thread.interrupted() ?
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }

        /**
         * Throws InterruptedException, reinterrupts current thread, or
         * does nothing, depending on mode.
         */
        private void reportInterruptAfterWait(int interruptMode)
                throws InterruptedException {
            // 报错模式则抛出中断异常
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            // 内嵌的中断模式则自我中断线程
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
        }

        /**
         * 条件队列上等待
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            // 添加条件等待节点
            Node node = addConditionWaiter();
            // 释放所有锁
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            // 如果该节点不在同步队列上则阻塞该线程
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                // 线程中断后跳出循环
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            // 进入同步队列获取锁，如果中断且中断模式不是报错，则表示内嵌再次中断
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            // 如果当前节点的下一个不为空，则清除取消的等待节点
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            // 如果中断则处理中断信息
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * 条件上等待指定纳秒
         */
        public final long awaitNanos(long nanosTimeout)
                throws InterruptedException {
            // 检查线程中断
            if (Thread.interrupted())
                throw new InterruptedException();
            // 条件队列添加等待节点
            Node node = addConditionWaiter();
            // 释放锁
            int savedState = fullyRelease(node);
            // 等待截至时间
            final long deadline = System.nanoTime() + nanosTimeout;
            int interruptMode = 0;
            // 如果不再同步队列上则则自旋等待
            while (!isOnSyncQueue(node)) {
                // 截止时间结束
                if (nanosTimeout <= 0L) {
                    // 迁移该节点到同步队列上
                    transferAfterCancelledWait(node);
                    break;
                }
                // 阻塞指定时间
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                // 中断模式
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                // 时间递减
                nanosTimeout = deadline - System.nanoTime();
            }
            // 同步队列上获取锁
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return deadline - System.nanoTime();
        }

        /**
         * 同awaitNanos方法一样，等待的是毫秒数
         */
        public final boolean awaitUntil(Date deadline)
                throws InterruptedException {
            long abstime = deadline.getTime();
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (System.currentTimeMillis() > abstime) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                LockSupport.parkUntil(this, abstime);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }

        /**
         * 等待指定单位的时长
         */
        public final boolean await(long time, TimeUnit unit)
                throws InterruptedException {
            long nanosTimeout = unit.toNanos(time);
            if (Thread.interrupted())
                throw new InterruptedException();
            Node node = addConditionWaiter();
            int savedState = fullyRelease(node);
            final long deadline = System.nanoTime() + nanosTimeout;
            boolean timedout = false;
            int interruptMode = 0;
            while (!isOnSyncQueue(node)) {
                if (nanosTimeout <= 0L) {
                    timedout = transferAfterCancelledWait(node);
                    break;
                }
                if (nanosTimeout >= spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
                nanosTimeout = deadline - System.nanoTime();
            }
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
            return !timedout;
        }


        /**
         * 返回被创建的实体状态
         */
        final boolean isOwnedBy(AbstractQueuedSynchronizer sync) {
            return sync == AbstractQueuedSynchronizer.this;
        }

        /**
         * 条件队列上是否有等待节点
         */
        protected final boolean hasWaiters() {
            // 只有在独占模式下可以
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            // 从条件队列的头节点开始，只要节点有条件状态则为true
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    return true;
            }
            return false;
        }

        /**
         * 获取条件队列上等待节点的数量
         */
        protected final int getWaitQueueLength() {
            // 只有在独占模式下可以
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            int n = 0;
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION)
                    ++n;
            }
            return n;
        }

        /**
         * 获取条件队列上等待节点的线程集合
         */
        protected final Collection<Thread> getWaitingThreads() {
            // 只有在独占模式下可以
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
                if (w.waitStatus == Node.CONDITION) {
                    Thread t = w.thread;
                    if (t != null)
                        list.add(t);
                }
            }
            return list;
        }
    }

    /**
     * CAS操作
     */
    private static final Unsafe unsafe = Unsafe.getUnsafe();
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {
            stateOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (AbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * CAS head field. Used only by enq.
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    /**
     * CAS tail field. Used only by enq.
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    /**
     * CAS waitStatus field of a node.
     */
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                expect, update);
    }

    /**
     * CAS next field of a node.
     */
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
}
