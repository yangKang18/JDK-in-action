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
import java.util.concurrent.locks.LockSupport;

/**
 * 异步执行任务，可获取异步处理后的结果
 * 获取结果的方式是阻塞的
 */
public class FutureTask<V> implements RunnableFuture<V> {


    /**
     * 任务的状态值：
     *
     * new：表示开始一个新任务
     * completing：完成后设置结果之前的状态
     * normal：正常完成设置结果值
     * exceptional：异常报错
     * cancelled：取消任务
     * interrupting：终断中
     * interrupted：已经中断
     *
     * 可能状态流转如下：
     * NEW -> COMPLETING -> NORMAL：新任务 -> 完成设置结果 -> 设置完成
     * NEW -> COMPLETING -> EXCEPTIONAL ： 新任务 -> 完成设置结果 -> 结果为异常报错
     * NEW -> CANCELLED ： 新任务 -> 取消任务（任务取消）
     * NEW -> INTERRUPTING -> INTERRUPTED ： 新任务 -> 中断任务（中断执行任务的线程）
     */
    private volatile int state;
    private static final int NEW          = 0;
    private static final int COMPLETING   = 1;
    private static final int NORMAL       = 2;
    private static final int EXCEPTIONAL  = 3;
    private static final int CANCELLED    = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED  = 6;

    /** 待执行的任务 */
    private Callable<V> callable;
    /** 执行结果 */
    private Object outcome;
    /** 执行任务的线程 */
    private volatile Thread runner;
    /** 等待获取结果的线程链表 */
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        // 如果是正常装填，直接返回结果
        if (s == NORMAL)
            return (V)x;
        // 如果取消则报取消错误
        if (s >= CANCELLED)
            throw new CancellationException();
        // 如果是异常则报执行异常错误
        throw new ExecutionException((Throwable)x);
    }

    /**
     * 构造函数
     * 支持Callable和Runnable两种参数，其中Runnable会转化为Callable
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;
    }


    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;
    }

    /** 任务状态如果大于取消则意味着是取消状态 */
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    /** 只要状态不是新任务状态，则视为完成，取消，中断，异常都视为完成任务 */
    public boolean isDone() {
        return state != NEW;
    }

    /**
     * 取消任务，参数mayInterruptIfRunning标记是否中断线程
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 只要不是运行中的状态，且CAS更新状态成功，则表示取消失败
        // 即有结果了就会取消失败，不论结果是取消，中断，异常
        if (!(state == NEW &&
                UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                        mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {
            // 如果中断线程标记为true，
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null) {
                        // 则中断执行该任务的线程
                        t.interrupt();
                    }
                } finally {
                    // 更新任务状态为线程已被中断
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * 获取结果
     */
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        // 如果状态值在设置结果之前，则等待完成
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        // 否则报告结果
        return report(s);
    }

    /**
     * 在指定时间内获取结果
     */
    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        // 时间单位不能为空
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        // 在结束设置结果之前并且等待后仍然没有完成的报超时错误
        //
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        // 未超时则报告结果
        return report(s);
    }

    /**
     * 取消任务完成
     */
    protected void done() { }

    /**
     * 设置正常结果
     */
    protected void set(V v) {
        // 更改new状态成功后，设置结果中，再更新状态
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL);
            finishCompletion();
        }
    }

    /**
     * 设置异常结果
     */
    protected void setException(Throwable t) {
        // 跟设置正常结果一样，设置异常结果
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL);
            finishCompletion();
        }
    }

    /**
     * 任务执行的方法
     */
    public void run() {
        // 如果不是新任务或者设置执行任务的线程失败，则直接返回
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            // 任务不为空并且是新任务才执行任务
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    // 执行任务
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                     // 设置异常，可能有业务异常，程序异常或者线程中断异常
                    setException(ex);
                }
                if (ran)
                    // 正常执行完成才设置结果值
                    set(result);
            }
        } finally {
            // 移除线程
            runner = null;
            // 如果中断线程，处理可能中断的错误
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * 执行任务但是不获取结果，如果报异常会设置异常结果，如果有异常结果，再比较状态是否为new时则会报错
     */
    protected boolean runAndReset() {
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // 只要在终结中，一直等待取消终结完成
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield();
    }

    /**
     * 等待任务的节点，单链结构
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * 执行结果完成
     * 唤醒所有等待结果的线程
     */
    private void finishCompletion() {
        // 等待结果的线程链，这里遍历唤醒
        for (WaitNode q; (q = waiters) != null;) {
            // 把任务中首个等待结果的线程设为null，设置成功，则逐一唤醒线程
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    // 如果下一个线程没有，则唤醒结束
                    if (next == null)
                        break;
                    // 断开链接，便于GC
                    q.next = null;
                    q = next;
                }
                break;
            }
        }

        // 钩子函数-完成
        done();
        // 清空任务
        callable = null;
    }

    /**
     * 等待结果完成
     */
    private int awaitDone(boolean timed, long nanos)
            throws InterruptedException {
        // 截止时间
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        for (;;) {
            // 如果当前线程中断，则移除该线程节点，并抛出中断异常
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
            // 如果任务完成，直接返回
            if (s > COMPLETING) {
                // 如果等待节点不为空，则清除节点上的线程引用，便于GC
                if (q != null)
                    q.thread = null;
                return s;
            }
            // 如果任务在设置结果中，直接让行，
            else if (s == COMPLETING)
                Thread.yield();
            // 如果此时还没结果，才把该线程设为等待节点
            else if (q == null)
                q = new WaitNode();
            // 如果该节点没有添加至链表，则把该队列添加至head，其next指针指向原waiter
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                        q.next = waiters, q);
            // 如果没有结果并且已经在队列里面时，检查超时情况
            else if (timed) {
                nanos = deadline - System.nanoTime();
                // 如果截至时间用完，则移除该线程节点并返回状态值
                if (nanos <= 0L) {
                    removeWaiter(q);
                    // 尽管此时超时，也是有种可能，在多线程环境下，state变成了完成，所以返回的不是s，而是state，state的可见性在此处一定已经完结
                    // 那麽awaitDone的返回值一定大于completing，那么调用report方法也就顺利返回结果了。
                    return state;
                }
                // 阻塞超时时间
                LockSupport.parkNanos(this, nanos);
            }
            else
                // 没有设置超时则一直阻塞
                LockSupport.park(this);
        }
    }

    /**
     * 移除节点
     * 单链中移除一个节点
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            // 清除该节点上的线程引用，目前的算法中，通过检查节点上线程引用为空来移除空节点
            node.thread = null;
            retry:
            for (;;) {
                // 从头开始遍历
                // pred指向当前节点之前的节点
                // q指向当前迭代的节点
                // s指向当前节点之后的节点
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    // 如果当前节点的线程不为空，即不为空节点，则pred指向该节点，为下一轮迭代的前驱节点
                    if (q.thread != null)
                        pred = q;
                    // 如果当前节点为空，前驱节点不为空，则前驱节点的next指向后驱节点
                    else if (pred != null) {
                        pred.next = s;
                        // 假如前驱节点也是空节点，则重新遍历
                        if (pred.thread == null)
                            continue retry;
                    }
                    // 如果当前节点为空，前驱节点也是空节点，也就是该空节点是首节点是，则直接移除，用后节点覆盖前节点，如果覆盖失败，自旋重新覆盖
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                            q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
