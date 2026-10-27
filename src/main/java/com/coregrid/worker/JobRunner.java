package com.coregrid.worker;

public interface JobRunner {
    ExecutionResult execute(Job job) throws Exception;
    /** Implementations can stop an already running job. */
    default void cancel(String jobId) throws Exception { }
}
