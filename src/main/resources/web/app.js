"use strict";

// The token is deliberately only held in memory; a refresh requires reconnecting.
const state = { token: null, jobs: [], selectedId: null, active: false, refreshTimer: null, busy: false, environments: [] };
const $ = id => document.getElementById(id);
const terminalStates = new Set(["SUCCEEDED", "FAILED", "TIMED_OUT", "REJECTED", "INTERRUPTED", "CANCELED"]);
const dateLabel = iso => { try { return new Date(iso).toLocaleString(); } catch { return iso || ""; } };

async function request(path, options = {}) {
  const headers = { Authorization: `Bearer ${state.token}` };
  if (options.body != null) headers["Content-Type"] = "application/json";
  const response = await fetch(path, { ...options, headers: { ...headers, ...(options.headers || {}) }, cache: "no-store" });
  if (options.download && response.ok) return response.blob();
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { error: text.slice(0, 240) }; }
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}
function notify(message, error = false) {
  const box = $("global-notice");
  box.textContent = message;
  box.classList.toggle("error", error);
  box.classList.remove("hidden");
}
function clearNotice() { $("global-notice").classList.add("hidden"); }
function loginUi(loggedIn) {
  $("signin-panel").classList.toggle("hidden", loggedIn);
  $("workspace").classList.toggle("hidden", !loggedIn);
  $("signout-btn").classList.toggle("hidden", !loggedIn);
  $("connection-status").classList.toggle("connected", loggedIn);
  $("connection-status").classList.toggle("disconnected", !loggedIn);
  $("connection-status").lastChild.textContent = loggedIn ? " Connected" : " Not connected";
}
async function connect(event) {
  event.preventDefault();
  const key = $("api-key").value.trim();
  $("signin-error").classList.add("hidden");
  if (!key) return;
  state.token = key;
  try {
    await request("/api/health");
    state.active = true;
    $("api-key").value = "";
    loginUi(true);
    await Promise.all([loadEnvironments(), refresh()]);
    state.refreshTimer = setInterval(() => refresh().catch(err => notify(err.message, true)), 4000);
  } catch (e) {
    state.token = null;
    state.active = false;
    loginUi(false);
    $("signin-error").textContent = `Connection failed: ${e.message}`;
    $("signin-error").classList.remove("hidden");
  }
}
function disconnect() {
  if (state.refreshTimer) clearInterval(state.refreshTimer);
  state.refreshTimer = null;
  state.active = false;
  state.token = null;
  state.selectedId = null;
  state.jobs = [];
  loginUi(false);
  clearNotice();
}
async function loadEnvironments() {
  const values = await request("/api/environments");
  state.environments = values;
  const select = $("environment");
  select.replaceChildren();
  for (const env of values) {
    const option = document.createElement("option");
    option.value = env.id;
    option.textContent = env.gpu ? `${env.id} · GPU` : `${env.id} · CPU`;
    select.appendChild(option);
  }
  if (!values.length) notify("No execution environments available.", true);
  updateEnvironmentHint();
}
function updateEnvironmentHint() {
  const chosen = state.environments.find(v => v.id === $("environment").value);
  $("env-hint").textContent = chosen ? `${chosen.gpu ? "GPU image" : "CPU image"}: ${chosen.image}. Runtime package installation is disabled.` : "Choose an approved environment.";
}
async function importFile() {
  const file = $("file-input").files[0];
  if (!file) return;
  if (!/\.py$/i.test(file.name)) { notify("Only .py scripts are supported in Phase 5.", true); return; }
  if (file.size > 64 * 1024) { notify("The Python file must be 64 KB or smaller.", true); return; }
  $("script-editor").value = await file.text();
  $("file-label").textContent = file.name;
  $("job-name").value = file.name.replace(/\.py$/i, "").slice(0, 80) || "Python job";
  clearNotice();
}
function sampleScript() {
  $("job-name").value = "Squares CSV";
  $("script-editor").value = `import csv\nfrom pathlib import Path\n\noutput = Path('/job/output/squares.csv')\nwith output.open('w', newline='') as f:\n    writer = csv.writer(f)\n    writer.writerow(['number', 'square'])\n    for number in range(1, 11):\n        writer.writerow([number, number * number])\n\nprint(f'Generated: {output}')\n`;
  $("file-label").textContent = "Example loaded; edit the script below";
  $("file-input").value = "";
  clearNotice();
}
function gpuSampleScript() {
  const env = state.environments.find(e => e.id === "pytorch-gpu");
  if (!env) { notify("PyTorch GPU environment not available. Check worker GPU image configuration.", true); return; }
  $("environment").value = "pytorch-gpu";
  $("memory").value = "4096";
  $("timeout").value = "300";
  $("job-name").value = "PyTorch CUDA Matrix Test";
  $("script-editor").value = `import torch
from pathlib import Path

assert torch.cuda.is_available(), "CUDA unavailable"
print("GPU:", torch.cuda.get_device_name(0))
print("PyTorch:", torch.__version__)
a = torch.randn((2000, 2000), device="cuda")
b = a @ a
torch.cuda.synchronize()
print("Result device:", b.device)
Path("/job/output").mkdir(exist_ok=True)
torch.save(b.cpu(), "/job/output/result.pt")
print("GPU computation successful!")
`;
  $("file-label").textContent = "GPU example loaded";
  $("file-input").value = "";
  $("args").value = "";
  updateEnvironmentHint();
  clearNotice();
}
function updateSourceMode() {
  const isGithub = $("source-kind").value === "github";
  $("github-source-fields").classList.toggle("hidden", !isGithub);
  $("single-source-fields").classList.toggle("hidden", isGithub);
}
async function submitJob(event) {
  event.preventDefault();
  if (state.busy) return;
  const github = $("source-kind").value === "github";
  const script = $("script-editor").value;
  if (!github && !script.trim()) { notify("Upload or enter a Python script first.", true); return; }
  if (!github && script.length > 65536) { notify("Python source exceeds 65,536 characters.", true); return; }
  if (github && (!$("repository-url").value.trim() || !$("repository-entry").value.trim())) {
    notify("Enter a public GitHub URL and Python entry point.", true); return;
  }
  const args = $("args").value.split(/\r?\n/).filter(s => s.length > 0);
  if (args.length > 16 || args.some(s => s.length > 200)) { notify("Use at most 16 arguments, up to 200 characters each.", true); return; }
  const env = state.environments.find(e => e.id === $("environment").value);
  if (!env) { notify("Choose an available environment.", true); return; }
  state.busy = true;
  $("submit-btn").disabled = true;
  $("submit-btn").textContent = "Submitting…";
  try {
    const payload = {
      name: $("job-name").value.trim(),
      ...(github ? { repositoryUrl: $("repository-url").value.trim(), ref: $("repository-ref").value.trim(), entryPoint: $("repository-entry").value.trim() } : { script }),
      environment: env.id, gpu: Boolean(env.gpu),
      cpu: Number($("cpu").value), memoryMb: Number($("memory").value),
      timeoutSeconds: Number($("timeout").value), args
    };
    const endpoint = github ? "/api/jobs/repository" : "/api/jobs";
    const result = await request(endpoint, { method: "POST", body: JSON.stringify(payload) });
    state.selectedId = result.id;
    notify(`Job submitted. ID: ${result.id}`);
    await refresh();
  } catch (e) { notify(`Submission failed: ${e.message}`, true); }
  finally { state.busy = false; $("submit-btn").disabled = false; $("submit-btn").textContent = "Submit job ↗"; }
}
async function refresh() {
  if (!state.active) return;
  const [health, jobs] = await Promise.all([request("/api/health"), request("/api/jobs")]);
  state.jobs = jobs;
  $("stat-worker").textContent = health.workerConnected ? "ONLINE" : "OFFLINE";
  $("stat-worker").style.color = health.workerConnected ? "var(--green)" : "var(--amber)";
  $("worker-sub").textContent = health.workerConnected
    ? (health.gpuReady ? "Agent connected · GPU ready" : "Agent connected · CPU mode")
    : "Waiting for agent";
  $("stat-queued").textContent = health.queueDepth ?? 0;
  $("stat-running").textContent = jobs.filter(j => ["RUNNING", "DISPATCHED", "CANCELING"].includes(j.status)).length;
  $("stat-done").textContent = jobs.filter(j => j.status === "SUCCEEDED").length;
  renderJobs();
  if (state.selectedId) await renderDetail();
}
function el(tag, cls, content) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (content != null) node.textContent = String(content);
  return node;
}
function renderJobs() {
  const target = $("job-list");
  target.replaceChildren();
  const filter = $("job-filter").value;
  const list = state.jobs.filter(j => filter === "all" ||
    (filter === "active" ? !terminalStates.has(j.status) : terminalStates.has(j.status)));
  if (!list.length) { target.appendChild(el("p", "empty", "No matching jobs.")); return; }
  for (const job of list) {
    const button = el("button", "job-row" + (state.selectedId === job.id ? " active" : ""));
    button.type = "button";
    const heading = el("div", "job-name-row");
    heading.append(el("span", "job-name", job.name));
    heading.append(el("span", "status " + job.status.toLowerCase().replace(/[^a-z_]/g, ""), job.status));
    const meta = el("div", "job-meta");
    meta.append(el("span", "", dateLabel(job.createdAt)));
    meta.append(el("span", "", job.environment));
    button.append(heading, meta);
    button.addEventListener("click", () => { state.selectedId = job.id; renderJobs(); renderDetail().catch(e => notify(e.message, true)); });
    target.appendChild(button);
  }
}
async function renderDetail() {
  const parent = $("job-detail");
  const job = state.jobs.find(j => j.id === state.selectedId);
  if (!job) { state.selectedId = null; parent.replaceChildren(el("p", "empty", "Select a job to view details.")); return; }
  parent.replaceChildren();
  parent.append(el("div", "detail-title", job.name));
  parent.append(el("div", "detail-identity", `Job ${job.id}`));
  const statusLine = el("div", "job-meta");
  statusLine.append(el("span", "status " + job.status.toLowerCase(), job.status));
  statusLine.append(el("span", "", `CPU ${job.cpu} · ${job.memoryMb} MB · ${job.gpu ? "GPU" : "CPU"}`));
  parent.append(statusLine);
  if (job.detail) parent.append(el("p", "field-note", job.detail));
  const actions = el("div", "detail-actions");
  if (["SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELED"].includes(job.status)) {
    const download = el("button", "button button-primary button-small", "↓ Download result ZIP");
    download.type = "button";
    download.addEventListener("click", () => downloadZip(job.id).catch(e => notify(e.message, true)));
    actions.append(download);
  }
  if (["QUEUED", "DISPATCHED", "RUNNING"].includes(job.status)) {
    const cancel = el("button", "button button-ghost button-small", "× Cancel job");
    cancel.type = "button";
    cancel.addEventListener("click", async () => {
      if (!window.confirm("Cancel this job? Any incomplete outputs may remain.")) return;
      cancel.disabled = true;
      try {
        const result = await request(`/api/jobs/${encodeURIComponent(job.id)}/cancel`, { method: "POST" });
        notify(`Cancellation requested: ${result.status}`);
        await refresh();
      } catch (e) { notify(`Cancellation failed: ${e.message}`, true); cancel.disabled = false; }
    });
    actions.append(cancel);
  }
  const logs = el("button", "button button-ghost button-small", "↻ Refresh logs");
  logs.type = "button";
  logs.addEventListener("click", () => renderDetail().catch(e => notify(e.message, true)));
  actions.append(logs);
  parent.append(actions);
  const source = await request(`/api/jobs/${encodeURIComponent(job.id)}/source`);
  if (source.type === "github") {
    parent.append(el("p", "field-note", `GitHub: ${source.repositoryUrl} · ${source.entryPoint} · commit ${source.commit.slice(0, 12)}`));
  }
  const data = await request(`/api/jobs/${encodeURIComponent(job.id)}/logs`);
  if (state.selectedId !== job.id) return;
  parent.append(el("div", "log-label", "STDOUT"), el("pre", "log-box", data.stdout || "No output yet."));
  parent.append(el("div", "log-label", "STDERR"), el("pre", "log-box stderr", data.stderr || "No errors."));
}
async function downloadZip(id) {
  const blob = await request(`/api/jobs/${encodeURIComponent(id)}/artifacts`, { download: true });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `coregrid-${id}.zip`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

$("source-kind").addEventListener("change", updateSourceMode);
updateSourceMode();
$("signin-form").addEventListener("submit", connect);
$("signout-btn").addEventListener("click", disconnect);
$("file-input").addEventListener("change", () => importFile().catch(e => notify(e.message, true)));
$("sample-btn").addEventListener("click", sampleScript);
$("gpu-sample-btn").addEventListener("click", gpuSampleScript);
$("job-form").addEventListener("submit", submitJob);
$("job-filter").addEventListener("change", renderJobs);
$("environment").addEventListener("change", updateEnvironmentHint);
$("refresh-btn").addEventListener("click", () => refresh().catch(e => notify(e.message, true)));
