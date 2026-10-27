package com.coregrid.worker;

/** Configuration-only diagnostics; does not require submitting an HTTP job. */
public final class GpuConfigSelfTest {
    public static void main(String[] args) {
        EnvironmentCatalog catalog = new EnvironmentCatalog();
        catalog.all().forEach(e -> System.out.println(e.id() + " -> " + e.image() + " [GPU=" + e.gpu() + "]"));
        GpuDetector.GpuInfo gpu = GpuDetector.detect();
        System.out.println("NVIDIA detection: " + gpu.description());
        if (catalog.contains("pytorch-gpu")) {
            String image = catalog.require("pytorch-gpu").image();
            boolean available = GpuDetector.localImagePresent(image);
            System.out.println("Cached image: " + available);
            if (gpu.present() && available)
                System.out.println("Docker PyTorch CUDA probe: " + GpuDetector.dockerCudaReady(image));
        } else {
            System.out.println("GPU environment disabled: set COREGRID_GPU_IMAGE");
        }
    }
}
