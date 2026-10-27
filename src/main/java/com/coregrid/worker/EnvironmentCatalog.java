package com.coregrid.worker;

import java.util.LinkedHashMap;
import java.util.Map;

/** Approved local images. Never permit user-controlled image references. */
public final class EnvironmentCatalog {
    public record Environment(String id, String image, boolean gpu) {}
    private final Map<String, Environment> environments = new LinkedHashMap<>();

    public EnvironmentCatalog() {
        environments.put("python-basic", new Environment("python-basic", "python:3.11-slim", false));
        registerIfConfigured("machine-learning", "COREGRID_ML_IMAGE", false);
        registerIfConfigured("pytorch-gpu", "COREGRID_GPU_IMAGE", true);
    }

    private void registerIfConfigured(String id, String variable, boolean gpu) {
        String value = System.getenv(variable);
        if (value != null && !value.isBlank()) {
            if (!value.matches("[a-zA-Z0-9._:/@-]{1,250}"))
                throw new IllegalArgumentException("Invalid configured image: " + variable);
            environments.put(id, new Environment(id, value, gpu));
        }
    }

    public Environment require(String id) {
        Environment env = environments.get(id);
        if (env == null) throw new IllegalArgumentException("Environment unavailable on this worker: " + id);
        return env;
    }

    public java.util.Collection<Environment> all() {
        return java.util.List.copyOf(environments.values());
    }
    public boolean contains(String id) { return environments.containsKey(id); }
}
