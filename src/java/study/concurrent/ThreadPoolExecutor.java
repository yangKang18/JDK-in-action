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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

/**
 * 线程池执行器
 *
 * @since 1.5
 * @author Doug Lea
 */
public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     * 该原子类表示线程池的状态，初始值为running的状态
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    /**
     * 线程数的位数，对32位的int类型，左3位为线程池的状态，剩余29位为线程数的个数
     */
    private static final int COUNT_BITS = Integer.SIZE - 3;
    /**
     * 1左移29位-1，则左3位为0，右边全部为1，即00011111111111111111111111111111，该二进制主要用于参与位运算，方便计算线程池的状态和线程个数
     */
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    /**
     * runState is stored in the high-order bits
     * 高3位表示线程池的状态：
     * running：     11100000000000000000000000000000
     * shutdown：    00000000000000000000000000000000
     * stop：        00100000000000000000000000000000
     * tidying：     01000000000000000000000000000000
     * terminated：  01100000000000000000000000000000
     * 由于线程池的状态值不多，取左3位即可
     */
    private static final int RUNNING    = -1 << COUNT_BITS;
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    private static final int STOP       =  1 << COUNT_BITS;
    private static final int TIDYING    =  2 << COUNT_BITS;
    private static final int TERMINATED =  3 << COUNT_BITS;

    /**
     * Packing and unpacking ctl
     * 获取线程池状态值和线程数量
     * capacity即00011111111111111111111111111111，在进行&运算时，高3位为0，剩余29位都是1，结果就表示29位的与值，即线程数量
     * ~capacity即11100000000000000000000000000000，在进行&运算时，高3位为1，剩余29位都是0，结果就表示高3位的与值，即可获得线程池的状态值
     */
    private static int runStateOf(int c)     { return c & ~CAPACITY; }
    private static int workerCountOf(int c)  { return c & CAPACITY; }

    /**
     * 原子类的int值，由表示高3位的线程池状态值与剩余29位的线程数量值或运算拼接为ctl
     */
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /**
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */
    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }
    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * CAS自增worker数量，可能自减失败，失败返回false
     */
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * CAS自减worker数量，可能自减失败，失败返回false
     */
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    /**
     * 自减worker数量，一定成功
     */
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }

    /**
     * 任务队列，存放需要执行的任务，这里使用阻塞队列，但需要注意队列的容量问题
     */
    private final BlockingQueue<Runnable> workQueue;

    /**
     * 可重入锁，主要保证每个worker消费任务
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * worker集合
     */
    private final HashSet<Worker> workers = new HashSet<Worker>();

    /**
     * 等待终结的条件
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * 最大线程数
     */
    private int largestPoolSize;

    /**
     * 完结的任务数量
     */
    private long completedTaskCount;

    /**
     * 创建线程的工厂类，主要用于创建线程
     */
    private volatile ThreadFactory threadFactory;

    /**
     * 拒绝消费任务的处理器
     */
    private volatile RejectedExecutionHandler handler;

    /**
     * 线程的生存时间
     */
    private volatile long keepAliveTime;

    /**
     * 核心线程数的超时时间
     */
    private volatile boolean allowCoreThreadTimeOut;

    /**
     * 核心线程数量
     */
    private volatile int corePoolSize;

    /**
     * 最大的线程数量
     */
    private volatile int maximumPoolSize;

    /**
     * 默认的拒绝策略处理器
     */
    private static final RejectedExecutionHandler defaultHandler =
            new AbortPolicy();

    /**
     * Permission required for callers of shutdown and shutdownNow.
     * We additionally require (see checkShutdownAccess) that callers
     * have permission to actually interrupt threads in the worker set
     * (as governed by Thread.interrupt, which relies on
     * ThreadGroup.checkAccess, which in turn relies on
     * SecurityManager.checkAccess). Shutdowns are attempted only if
     * these checks pass.
     *
     * All actual invocations of Thread.interrupt (see
     * interruptIdleWorkers and interruptWorkers) ignore
     * SecurityExceptions, meaning that the attempted interrupts
     * silently fail. In the case of shutdown, they should not fail
     * unless the SecurityManager has inconsistent policies, sometimes
     * allowing access to a thread and sometimes not. In such cases,
     * failure to actually interrupt threads may disable or delay full
     * termination. Other uses of interruptIdleWorkers are advisory,
     * and failure to actually interrupt will merely delay response to
     * configuration changes so is not handled exceptionally.
     */
    private static final RuntimePermission shutdownPerm =
            new RuntimePermission("modifyThread");

    /**
     * 任务线程对象
     */
    private final class Worker extends AbstractQueuedSynchronizer implements Runnable
    {
        /**
         * This class will never be serialized, but we provide a
         * serialVersionUID to suppress a javac warning.
         */
        private static final long serialVersionUID = 6138294804551838833L;

        /** 任务线程 */
        final Thread thread;
        /** 初始化的任务，也能为null */
        Runnable firstTask;
        /** 完成的任务数 */
        volatile long completedTasks;

        /**
         * 构造方法
         */
        Worker(Runnable firstTask) {
            // 指定同步器状态
            setState(-1);
            // 设置任务，可能为null
            this.firstTask = firstTask;
            // 创建线程
            this.thread = getThreadFactory().newThread(this);
        }

        /** 执行任务 */
        public void run() {
            runWorker(this);
        }

        /**
         * 是否独占模式，初始值为-1，也就是初始默认是线程独占的
         */
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        /**
         * 尝试获取锁
         */
        protected boolean tryAcquire(int unused) {
            // 由于状态值为0时，才可以获取锁，所以更改同步状态值时期望值是0，如果不是0，说明锁被独占
            if (compareAndSetState(0, 1)) {
                // 获取锁成功则设置当前独占的线程
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        /**
         * 释放锁
         */
        protected boolean tryRelease(int unused) {
            // 清除独占锁线程，同步状态值改为0
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        /**
         * 中断任务
         */
        void interruptIfStarted() {
            Thread t;
            // 锁被占用（有任务），该线程不为空且未中断，则中断任务线程
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    /*
     * Methods for setting control state
     */

    /**
     * 直接设置线程池运行状态
     */
    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            // 如果当前状态超过指定状态值，或者当前值设置指定值成功则返回
            if (runStateAtLeast(c, targetState) ||
                    ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     * 尝试终结线程池
     */
    final void tryTerminate() {
        // 自旋处理
        for (;;) {
            // 获取状态值，如果线程池是running状态或者，已经在整理，终结状态，或者在关闭时但是任务队列还有任务时，不做任何处理
            int c = ctl.get();
            if (isRunning(c) ||
                    runStateAtLeast(c, TIDYING) ||
                    (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;
            // 如果任务线程数量不为0，则中断空闲的任务线程，只中断1个任务线程
            if (workerCountOf(c) != 0) {
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            // 此时线程池在关闭中，且任务线程数为0，终结线程池
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                // 更改状态值为整理状态
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        // 终结
                        terminated();
                    } finally {
                        // 设置状态值为终结状态
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    /*
     * Methods for controlling interrupts to worker threads.
     */

    /**
     * If there is a security manager, makes sure caller has
     * permission to shut down threads in general (see shutdownPerm).
     * If this passes, additionally makes sure the caller is allowed
     * to interrupt each worker thread. This might not be true even if
     * first check passed, if the SecurityManager treats some threads
     * specially.
     */
    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    /**
     * Interrupts all threads, even if active. Ignores SecurityExceptions
     * (in which case some threads may remain uninterrupted).
     */
    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 遍历任务线程，中断已启动的线程
            for (Worker w : workers)
                w.interruptIfStarted();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 中断空闲线程
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 遍历线程集合
            for (Worker w : workers) {
                Thread t = w.thread;
                // 如果该任务线程没有中断，且当前线程能拿到锁，则中断该线程（当前线程能拿到锁，意味着该任务线程没有正在执行的任务，是空闲的线程）
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                // 1次中断1个任务线程的标记
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Common form of interruptIdleWorkers, to avoid having to
     * remember what the boolean argument means.
     */
    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    /*
     * Misc utilities, most of which are also exported to
     * ScheduledThreadPoolExecutor
     */

    /**
     * Invokes the rejected execution handler for the given command.
     * Package-protected for use by ScheduledThreadPoolExecutor.
     */
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    /**
     * Performs any further cleanup following run state transition on
     * invocation of shutdown.  A no-op here, but used by
     * ScheduledThreadPoolExecutor to cancel delayed tasks.
     */
    void onShutdown() {
    }

    /**
     * State check needed by ScheduledThreadPoolExecutor to
     * enable running tasks during shutdown.
     *
     * @param shutdownOK true if should return true if SHUTDOWN
     */
    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     * 导出队列中剩余的任务，产生新的列表对象，workQueue会清空
     */
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        q.drainTo(taskList);
        if (!q.isEmpty()) {
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r))
                    taskList.add(r);
            }
        }
        return taskList;
    }

    /**
     * 添加任务线程
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        retry:
        for (;;) {
            // 获取线程池的状态
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            // 如果线程池关闭以上状态，在关闭状态时，任务为空但是队列不为空时，添加任务线程失败
            if (rs >= SHUTDOWN &&
                    ! (rs == SHUTDOWN &&
                            firstTask == null &&
                            ! workQueue.isEmpty()))
                return false;

            // 自旋添加任务
            for (;;) {
                // 获取任务线程数量，如果线程数超过容量或者超过指定线程数量（指定核心则不能超过核心数，指定最大则不能超过最大线程数）时，返回添加失败
                int wc = workerCountOf(c);
                if (wc >= CAPACITY ||
                        wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;
                // 自增任务线程数量，自增成功则跳出循环
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                // 没有自增成功，再次检查状态值，如果线程池状态值发生变化，则重新检查状态
                c = ctl.get();  // Re-read ctl
                if (runStateOf(c) != rs)
                    continue retry;
                // 如果没有发生改变，则内部自旋内部循环进行CAS自增
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            // 新建任务线程
            w = new Worker(firstTask);
            final Thread t = w.thread;
            // 每个任务都有一个新的线程取执行任务，如果没有线程则不用处理，也说明任务线程有问题
            if (t != null) {
                // 加锁，保证线程集合和最大线程数临界值安全
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    // 获取线程池状态
                    int rs = runStateOf(ctl.get());

                    // 只有线程池是running，或者是关闭时但是任务为null（也就是单纯的添加线程，不携带任务）
                    if (rs < SHUTDOWN ||
                            (rs == SHUTDOWN && firstTask == null)) {
                        // 新添加的线程不是激活的，如果是激活就绪的，则线程不合法
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();
                        // 任务线程添加该线程
                        workers.add(w);
                        int s = workers.size();
                        // 更新最大线程数的值，这是最大临界值的记录
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                // 任务添加成功则启动任务
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            // 任务启动失败则该任务线程失败
            if (! workerStarted)
                addWorkerFailed(w);
        }
        return workerStarted;
    }

    /**
     * 添加任务线程失败
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        // 加锁，保证任务线程集合安全操作
        mainLock.lock();
        try {
            // 如果任务线程不为空，则移除该任务线程
            if (w != null)
                workers.remove(w);
            // 自减任务线程数量
            decrementWorkerCount();
            // 尝试终结
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 任务线程关闭退出
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        // 突然完结标记，任务线程完成所有任务后，就是正常完成，正常完成的都已经自减了任务线程数量，线程报错导致完结的时没有自减的，这里需要自减一下
        if (completedAbruptly)
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 累加线程总任务完成数，并从任务线程集合中移除
            completedTaskCount += w.completedTasks;
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        // 尝试终结
        tryTerminate();

        int c = ctl.get();
        // 线程池状态小于stop的状态时，还需要维护线程池中线程的数量
        if (runStateLessThan(c, STOP)) {
            if (!completedAbruptly) {

                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;
                // 当任务线程数已经大于核心线程数是，不用做任何处理
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }
            // 但核心线程数不够或者突然中断的任务时，都需要补充一个任务线程
            addWorker(null, false);
        }
    }

    /**
     * 获取任务
     */
    private Runnable getTask() {
        // Did the last poll() time out?
        boolean timedOut = false;

        // 自旋获取任务
        for (;;) {
            // 获取线程池状态值
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            // 如果线程池关闭，并且线程池已停止或者任务队列为空，则自减任务线程数量
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            // 任务线程数量
            int wc = workerCountOf(c);
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            // 如果任务线程数量大于最大线程数设置，或者超时，并且任务线程数大于1但是任务队列为空时，自减任务线程成功后返回null
            if ((wc > maximumPoolSize || (timed && timedOut))
                    && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            try {
                // 如果允许核心线程超时，那么在获取任务时，如果超时时间内未获取到任务，则任务为null
                // 工作线程如果获取的任务为null，则会关闭任务线程
                Runnable r = timed ?
                        workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                        workQueue.take();
                if (r != null)
                    return r;
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /**
     * 任务线程执行任务
     */
    final void runWorker(Worker w) {
        // 执行任务的当前线程
        Thread wt = Thread.currentThread();
        // 线程任务携带的任务
        Runnable task = w.firstTask;
        w.firstTask = null;
        // 清除锁和独占线程
        w.unlock();
        boolean completedAbruptly = true;
        try {
            // 只要任务不为空或获取的任务不为空就执行任务，此处任务的获取除了初始化携带的任务，就是从任务队列中获取
            while (task != null || (task = getTask()) != null) {
                //
                w.lock();
                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted.  This
                // requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt
                // 如果线程池停止，或者线程中断并且当前任务线程未中断，则中断当前线程
                if ((runStateAtLeast(ctl.get(), STOP) ||
                        (Thread.interrupted() &&
                                runStateAtLeast(ctl.get(), STOP))) &&
                        !wt.isInterrupted())
                    wt.interrupt();
                try {
                    // 钩子函数，开始执行之前处理
                    beforeExecute(wt, task);
                    Throwable thrown = null;
                    try {
                        // 执行任务
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        // 钩子函数，执行之后的处理
                        afterExecute(task, thrown);
                    }
                } finally {
                    // 不管结果如何，执行完了，清除任务，该线程完成数量自增
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            completedAbruptly = false;
        } finally {
            processWorkerExit(w, completedAbruptly);
        }
    }

    // Public constructors and methods

    /**
     * 4个构造函数，核心线程数，最大线程数，线程生存时间，时间单位，任务队列是必传参数
     * 线程工厂和拒绝策略非必选时有对应的默认值
     */
    public ThreadPoolExecutor(int corePoolSize,int maximumPoolSize,long keepAliveTime,TimeUnit unit,BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,Executors.defaultThreadFactory(), defaultHandler);
    }

    public ThreadPoolExecutor(int corePoolSize,int maximumPoolSize,long keepAliveTime,TimeUnit unit,BlockingQueue<Runnable> workQueue,ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,threadFactory, defaultHandler);
    }

    public ThreadPoolExecutor(int corePoolSize,int maximumPoolSize,long keepAliveTime,TimeUnit unit,BlockingQueue<Runnable> workQueue,RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,Executors.defaultThreadFactory(), handler);
    }

    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        // 参数校验
        // 核心线程小于0，或者最大线程数小于0，或者最大线程数小于核心线程数，或者生存时间小于0，都是不合法的参数
        if (corePoolSize < 0 ||
                maximumPoolSize <= 0 ||
                maximumPoolSize < corePoolSize ||
                keepAliveTime < 0)
            throw new IllegalArgumentException();
        // 如果任务队列为空，或者线程工厂为空，或者拒绝策略处理器为空，也不合法
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        // 设置重要的初始值
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * 核心方法，执行任务
     */
    public void execute(Runnable command) {
        // 如果任务为空，抛错
        if (command == null)
            throw new NullPointerException();

        // 获取状态值
        int c = ctl.get();
        // 如果worker数量小于核心线程数，则添加线程
        if (workerCountOf(c) < corePoolSize) {
            if (addWorker(command, true))
                return;
            // 添加任务线程失败（要么线程池状态变更，要么任务线程数满了），重新查询状态值
            c = ctl.get();
        }

        // 如果线程池是运行状态，并且任务队列入队成功
        if (isRunning(c) && workQueue.offer(command)) {
            // 重新检查状态，如果不再是运行状态则队列中移除该任务并拒绝任务
            int recheck = ctl.get();
            if (! isRunning(recheck) && remove(command))
                reject(command);
            // 如果工作线程数没有了，则增加线程
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        }
        // 再次添加失败，则拒绝任务
        else if (!addWorker(command, false))
            reject(command);
    }

    /**
     * 关闭线程池
     * 只是更改了线程池的状态，清空了空闲任务线程，有任务的线程是没有处理的
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 检查任务线程权限
            checkShutdownAccess();
            // 设置状态为shutdown
            advanceRunState(SHUTDOWN);
            // 中断所有空闲任务线程
            interruptIdleWorkers();
            // 执行关闭，钩子函数
            onShutdown(); // hook for ScheduledThreadPoolExecutor
        } finally {
            mainLock.unlock();
        }
        // 尝试中断
        tryTerminate();
    }

    /**
     * 立刻停止
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 检查任务线程权限
            checkShutdownAccess();
            // 设置状态为shutdown
            advanceRunState(STOP);
            // 中断所有任务线程
            interruptWorkers();
            // 获取队列中的任务列表
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        // 尝试中断
        tryTerminate();
        return tasks;
    }

    /**
     * 不是running的状态就是关闭
     */
    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }

    /**
     * 不是running状态且小于terminated的状态
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    /**
     * 至少是terminated的状态
     */
    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    /**
     * 等待终结
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                if (runStateAtLeast(ctl.get(), TERMINATED))
                    return true;
                if (nanos <= 0)
                    return false;
                // 中断条件队列
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Invokes {@code shutdown} when this executor is no longer
     * referenced and it has no threads.
     */
    protected void finalize() {
        shutdown();
    }

    /**
     * 设置线程工厂
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * 返回线程工厂
     */
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * 设置拒绝处理器
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    /**
     * 获取拒绝处理器
     */
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * 设置核心线程数
     */
    public void setCorePoolSize(int corePoolSize) {
        // 设置的数字不能小于0
        if (corePoolSize < 0)
            throw new IllegalArgumentException();
        // 记录新旧核心线程数差值，并设置新值
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        // 如果任务线程数大于核心线程数，则移除所有空闲任务线程
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();

        // 如果任务线程数小于核心线程数，并且设置的线程数有所减小，则不处理，如果有所增大，则需要添加核心线程数
        else if (delta > 0) {
            // 取查值和任务数量的最小值，然后添加核心线程，如果任务队列为空则不再添加，也就是说核心数并不是初始化好的，而是随着任务增加后新创建的
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    /**
     * 获取核心线程数
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * 启动一个核心任务线程
     */
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
                addWorker(null, true);
    }

    /**
     * 启动一个任务线程，有可能核心线程为0，
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    /**
     * 启动所有的核心任务线程
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * 是否允许核心线程超时
     */
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * 设置核心线程超时
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            // 如果允许，则中断所有空闲线程
            if (value)
                interruptIdleWorkers();
        }
    }

    /**
     * 设置最大线程数
     */
    public void setMaximumPoolSize(int maximumPoolSize) {
        // 最大线程数小于0或者小于核心线程数，视为非法参数
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        // 如果当前任务线程数量大于最大任务数，则中断所有空闲任务线程
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    /**
     * 获取最大线程数量
     */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * 设置核心线程的生存时间
     */
    public void setKeepAliveTime(long time, TimeUnit unit) {
        // 生存时间小于0或者等于0但是又允许超时设置，视为非法参数
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        // 超时时间查值变小，中断所有空闲任务线程
        if (delta < 0)
            interruptIdleWorkers();
    }

    /**
     * 获取生存时间
     */
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }


    /**
     * 获取任务队列
     */
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * 移除任务
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * 如果任务队列中又Future类型的任务被取消，则移除
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }

    /**
     * 获取线程池容量，即任务线程集合的大小
     */
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 如果线程池状态至少在整理阶段，返回0，其他取任务线程集合的大小
            return runStateAtLeast(ctl.get(), TIDYING) ? 0
                    : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取有执行任务的线程数量
     */
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                // 该任务被独占，说明有任务在执行
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取最大线程临界值
     */
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取任务数量，包含线程池完成的数量，任务线程完成的数量，正在执行的数量和任务队列中的数量
     */
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 线程池完成的数量
            long n = completedTaskCount;
            for (Worker w : workers) {
                // 任务线程完成的数量
                n += w.completedTasks;
                if (w.isLocked())
                    // 正在执行的数量
                    ++n;
            }
            // 任务队列中的数量
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 获取所有已完成的数量，包含线程池完成的数量，任务线程完成的数量
     */
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 线程池完成的数量
            long n = completedTaskCount;
            for (Worker w : workers)
                // 任务线程完成的数量
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * 字符串化
     */
    public String toString() {
        // 已完成任务数
        long ncompleted;
        // 任务线程数，有执行任务的线程数
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running" :
                (runStateAtLeast(c, TERMINATED) ? "Terminated" :
                        "Shutting down"));
        return super.toString() +
                "[" + rs +
                ", pool size = " + nworkers +
                ", active threads = " + nactive +
                ", queued tasks = " + workQueue.size() +
                ", completed tasks = " + ncompleted +
                "]";
    }

    /* Extension hooks */

    /**
     * 钩子函数
     */
    protected void beforeExecute(Thread t, Runnable r) { }

    /**
     * 钩子函数
     */
    protected void afterExecute(Runnable r, Throwable t) { }

    /**
     * Method invoked when the Executor has terminated.  Default
     * implementation does nothing. Note: To properly nest multiple
     * overridings, subclasses should generally invoke
     * {@code super.terminated} within this method.
     */
    protected void terminated() { }

    /* Predefined RejectedExecutionHandlers */

    /**
     * 当前线程处理的处理器
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code CallerRunsPolicy}.
         */
        public CallerRunsPolicy() { }

        /**
         * 当前的线程直接执行，而不是使用线程池中的线程
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /**
     * 中止策略处理器
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        /**
         * Creates an {@code AbortPolicy}.
         */
        public AbortPolicy() { }

        /**
         * 总是抛异常
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                    " rejected from " +
                    e.toString());
        }
    }

    /**
     * 丢弃任务不处理处理器
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardPolicy}.
         */
        public DiscardPolicy() { }

        /**
         * 丢弃任务，啥也不用做
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    /**
     * 丢弃最老的任务处理器
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardOldestPolicy} for the given executor.
         */
        public DiscardOldestPolicy() { }

        /**
         * 从线程池中的阻塞队列中取出head的元素，再执行该任务
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
