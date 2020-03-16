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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 栅栏同步器
 * 当指定数量的线程都到达时，再一起往下执行
 */
public class CyclicBarrier {
    /**
     * 栅栏生成标记，默认栅栏是完好的
     */
    private static class Generation {
        boolean broken = false;
    }

    /** 可重入锁 */
    private final ReentrantLock lock = new ReentrantLock();
    /** 条件队列 */
    private final Condition trip = lock.newCondition();
    /** 允许进入栅栏内数量 */
    private final int parties;
    /** 栅栏满了是执行的异步任务 */
    private final Runnable barrierCommand;
    /** 当前栅栏生成状态 */
    private Generation generation = new Generation();

    /**
     * 栅栏剩余等待的数量
     */
    private int count;

    /**
     * 生成新的栅栏
     */
    private void nextGeneration() {
        // signal completion of last generation
        trip.signalAll();
        // set up next generation
        count = parties;
        generation = new Generation();
    }

    /**
     * 破坏栅栏，所有等待着一起运行
     */
    private void breakBarrier() {
        generation.broken = true;
        count = parties;
        trip.signalAll();
    }

    /**
     * 栅栏等待
     */
    private int dowait(boolean timed, long nanos)
            throws InterruptedException, BrokenBarrierException,
            TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 检查栅栏是否破坏，如果已经被破坏，则报错
            // 由于报错的那一个会打开栅栏并唤醒所有等待的线程，新进来的线程只要栅栏破坏了报错即可
            final Generation g = generation;
            if (g.broken)
                throw new BrokenBarrierException();

            // 如果线程中断，破坏栅栏，唤醒其他的等待者，抛出中断错误
            if (Thread.interrupted()) {
                breakBarrier();
                throw new InterruptedException();
            }

            // 检查剩余数量
            int index = --count;
            // 如果是最后一个数量
            if (index == 0) {
                boolean ranAction = false;
                try {
                    // 如果栅栏任务不为空，则执行任务，同时生成新栅栏，原来的栅栏的等待着全部唤醒
                    final Runnable command = barrierCommand;
                    if (command != null)
                        command.run();
                    ranAction = true;
                    nextGeneration();
                    return 0;
                } finally {
                    // 如果执行状态失败，也需要打开栅栏
                    if (!ranAction)
                        breakBarrier();
                }
            }

            // 如果不是最后一个名额，则自旋等待
            for (;;) {
                try {
                    // 条件队列等待唤醒
                    if (!timed)
                        trip.await();
                    else if (nanos > 0L)
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    // 如果线程中断，原来的栅栏生成没有打开，这打开栅栏，否则中断当前线程
                    if (g == generation && ! g.broken) {
                        breakBarrier();
                        throw ie;
                    } else {
                        // We're about to finish waiting even if we had not
                        // been interrupted, so this interrupt is deemed to
                        // "belong" to subsequent execution.
                        Thread.currentThread().interrupt();
                    }
                }

                // 如果栅栏破坏，则报错
                // 如果栅栏正常打开，不会报错
                if (g.broken)
                    throw new BrokenBarrierException();

                // 如果栅栏变了，返回当前第几个等待着
                if (g != generation)
                    return index;

                // 如果超时但是时间小于0的设置，破坏栅栏并报错
                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 构造函数
     * 指定栅栏内等待数量和栅栏操作任务
     */
    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    /**
     * 构造函数
     */
    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    /**
     * 获取栅栏内允许的等待的数量
     */
    public int getParties() {
        return parties;
    }

    /**
     * 栅栏等待
     */
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe);
        }
    }

    /**
     * 栅栏等待指定时间
     */
    public int await(long timeout, TimeUnit unit)
            throws InterruptedException,
            BrokenBarrierException,
            TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    /**
     * 栅栏是否打开
     */
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置栅栏
     */
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 打开当前栅栏
            breakBarrier();
            // 生成新栅栏
            nextGeneration();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 栅栏等待的数量
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
