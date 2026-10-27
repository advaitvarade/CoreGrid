package com.coregrid.worker;

import java.nio.file.Path;

public record ExecutionResult(int exitCode, boolean timedOut, Path stdout, Path stderr, Path output) {}
