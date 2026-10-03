package org.python.pegen;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs work that recurses as deeply as its input nests (parsing, and the
 * compiler stages that walk the AST) on a thread with a {@link #STACK_SIZE}
 * stack, so that input CPython accepts doesn't overflow the caller's stack.
 * C stops such recursion with its own limits (MAXSTACK in the parser,
 * Py_EnterRecursiveCall in the compiler), and those, not the caller's thread
 * stack, should be the limit in practice.
 */
public final class LargeStack {

    private LargeStack() {}

    /**
     * The stack size of the threads work runs on. Input nested close to the
     * parser's MAXSTACK needs about 1.25 MB to parse (about 210 bytes per
     * level, interpreted or compiled, in either pass; HotSpot on 64-bit arm),
     * so this leaves a wide margin. A thread's stack is reserved address
     * space, committed only as it is used.
     */
    public static final long STACK_SIZE = 16L * 1024 * 1024;

    /** A thread with a {@link #STACK_SIZE} stack. */
    private static final class LargeStackThread extends Thread {
        private static final AtomicInteger count = new AtomicInteger();

        LargeStackThread(Runnable r) {
            super(null, r, "pegen-large-stack-" + count.incrementAndGet(), STACK_SIZE);
            setDaemon(true);
        }
    }

    /** Runs work on LargeStackThreads; idle threads exit after a minute. */
    private static final ExecutorService threads =
            Executors.newCachedThreadPool(LargeStackThread::new);

    /**
     * work's result, computed on a thread with a {@link #STACK_SIZE} stack
     * (this one, if it is such a thread). What work throws is rethrown here.
     */
    public static <T> T call(Supplier<T> work) {
        if (Thread.currentThread() instanceof LargeStackThread) {
            return work.get();
        }
        Future<T> result = threads.submit(work::get);
        // The work is bounded, so wait for it even if interrupted, then
        // restore the interrupt.
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return result.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
