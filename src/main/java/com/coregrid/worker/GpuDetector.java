package com.coregrid.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Reads host NVIDIA GPU information. Not a VRAM quota or isolation boundary. */
public final class GpuDetector {
    public record GpuInfo(boolean present, String description) {}

    public static GpuInfo detect() {
        Process p = null;
        try {
            p = new ProcessBuilder("nvidia-smi", "--query-gpu=index,name,memory.total",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new GpuInfo(false, "nvidia-smi timed out");
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.exitValue() != 0 || output.isBlank()) return new GpuInfo(false, "No NVIDIA GPU detected");
            String first = output.lines().findFirst().orElse("");
            return new GpuInfo(true, first);
        } catch (IOException e) {
            return new GpuInfo(false, "nvidia-smi not available");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GpuInfo(false, "GPU detection interrupted");
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** Executes a trusted CUDA availability probe inside the configured cached image. */
    public static boolean dockerCudaReady(String image) {
        try {
            Process p = new ProcessBuilder("docker", "run", "--rm", "--pull=never",
                    "--network=none", "--gpus", "device=0", image,
                    "python", "-c", "import torch; assert torch.cuda.is_available()")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(45, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    public static boolean localImagePresent(String image) {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
}
