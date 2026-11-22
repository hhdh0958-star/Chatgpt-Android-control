"use strict";

// This realm can exchange bounded results with the service. Project code runs only in a child worker.
let completed = null;
let worker = null;
function runProject(request) {
    if (worker || completed) return;
    worker = request.entry.endsWith(".py") ? new Worker("worker.js") :
        new Worker("script-worker.js", {type: "module"});
    const channel = new MessageChannel();
    const timeout = setTimeout(() => finish(-1, "", "Local execution timed out", true, false), 20000);
    function finish(exitCode, stdout, stderr, timedOut, truncated) {
        if (completed) return;
        completed = {exitCode, stdout, stderr, timedOut, truncated};
        clearTimeout(timeout);
        channel.port1.close();
        worker.terminate();
    }
    channel.port1.onmessage = event => {
        const result = event.data;
        if (!result || !Number.isSafeInteger(result.exitCode) ||
            typeof result.stdout !== "string" || typeof result.stderr !== "string") {
            finish(-1, "", "Invalid worker result", false, false);
            return;
        }
        finish(result.exitCode, result.stdout.slice(0, 2048), result.stderr.slice(0, 2048),
            false, Boolean(result.truncated || result.stdout.length > 2048 || result.stderr.length > 2048));
    };
    worker.onerror = () => finish(-1, "", "Could not start the bundled worker", false, false);
    worker.postMessage(request, [channel.port2]);
}
