package com.coregrid.network;

import java.util.List;

/** Version 2 length-framed TCP message. Only local Coordinator/Worker should use this. */
public record WireMessage(String type, String jobId, int cpu, boolean gpu,
                          int exitCode, String detail, String environment,
                          List<String> arguments, int memoryMb, int timeoutSeconds) {
    public WireMessage {
        if (type == null || !type.matches("[A-Z_]{2,24}")) throw new IllegalArgumentException("Invalid message type");
        if (jobId == null || jobId.length() > 40 || detail == null || detail.length() > 4000)
            throw new IllegalArgumentException("Invalid message fields");
        if (environment == null || environment.length() > 64 || arguments == null || arguments.size() > 16)
            throw new IllegalArgumentException("Invalid execution options");
        arguments = List.copyOf(arguments);
    }
    public static WireMessage simple(String type, String jobId, String detail) {
        return new WireMessage(type, jobId, 0, false, 0, detail, "python-basic", List.of(), 512, 120);
    }
    public static WireMessage run(String id, int cpu, boolean gpu) {
        return run(id, cpu, gpu, gpu ? "pytorch-gpu" : "python-basic", List.of(), 512, 120);
    }
    public static WireMessage run(String id, int cpu, boolean gpu, String env, List<String> args,
                                  int mem, int timeout) {
        return new WireMessage("RUN_JOB", id, cpu, gpu, 0, "", env, args, mem, timeout);
    }
    public static WireMessage outcome(String type, String id, int exitCode, String detail) {
        return new WireMessage(type, id, 0, false, exitCode, detail, "python-basic", List.of(), 512, 120);
    }
}
