package com.coregrid.worker;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local scheduling budgets, not OS-enforced CPU-core or VRAM reservations. */
public final class ResourceManager {
    private final Semaphore cpuPermits;
    private final Semaphore gpuPermits;
    private final int cpuCapacity;
    private final int gpuCapacity;

    public ResourceManager(int cpuCapacity, int gpuCapacity) {
        if (cpuCapacity < 1 || gpuCapacity < 0) throw new IllegalArgumentException("Invalid capacity");
        this.cpuCapacity = cpuCapacity;
        this.gpuCapacity = gpuCapacity;
        cpuPermits = new Semaphore(cpuCapacity, true);
        gpuPermits = new Semaphore(gpuCapacity, true);
    }

    public Reservation acquire(Job job) throws InterruptedException {
        if (job.cpuCores() > cpuCapacity) {
            throw new IllegalArgumentException("Job requests more CPU than total scheduling capacity");
        }
        // Always acquire GPU first, then CPU. Release both on every path.
        boolean acquiredGpu = false;
        try {
            if (job.requiresGpu()) {
                if (gpuCapacity == 0) {
                    throw new IllegalArgumentException("No GPU available for this scheduler");
                }
                gpuPermits.acquire();
                acquiredGpu = true;
            }
            cpuPermits.acquire(job.cpuCores());
            return new Reservation(job.cpuCores(), acquiredGpu);
        } catch (InterruptedException | RuntimeException e) {
            if (acquiredGpu) gpuPermits.release();
            throw e;
        }
    }

    public int availableCpuPermits() { return cpuPermits.availablePermits(); }

    public final class Reservation implements AutoCloseable {
        private final int cpuCores;
        private final boolean gpu;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(int cpuCores, boolean gpu) {
            this.cpuCores = cpuCores;
            this.gpu = gpu;
        }

        @Override public void close() {
            if (released.compareAndSet(false, true)) {
                cpuPermits.release(cpuCores);
                if (gpu) gpuPermits.release();
            }
        }
    }
}
