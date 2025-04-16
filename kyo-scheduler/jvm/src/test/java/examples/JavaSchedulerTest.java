package examples;

import kyo.scheduler.Scheduler;
import kyo.scheduler.Task;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class JavaSchedulerTest {

    public static void main(String[] args) throws Exception {
        forks();
    }

    private static void forks() throws Exception {
        Scheduler scheduler = Scheduler.get();

        AtomicReference<String> threadName = new AtomicReference<>("");
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.schedule(Task.apply(() -> {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        }));

        if (!latch.await(1, TimeUnit.SECONDS)) {
            throw new Exception("Task did not complete in time");
        }

        if (!threadName.get().contains("kyo")) {
            throw new Exception("Task did not run on kyo thread, ran on: " + threadName.get());
        } else {
            System.out.println("Task ran on thread: " + threadName.get());
        }
    }
}
