"use strict";

// Only the private channel carries completion; messages on the public worker port are ignored.
self.onmessage = async ({data: {entry, files}, ports: [port]}) => {
    self.onmessage = null;
    let stdout = "", stderr = "", truncated = false, exitCode = 0;
    function append(error, value) {
        const current = error ? stderr : stdout;
        truncated ||= value.length + current.length > 2048;
        const next = current + value.slice(0, 2048 - current.length);
        if (error) stderr = next; else stdout = next;
    }
    try {
        for (const name of ["Worker", "SharedWorker"]) {
            Object.defineProperty(self, name, {value: undefined, writable: false, configurable: false});
        }
        if (entry.endsWith(".sh")) {
            const {Bash} = await import("./shell-runtime.js");
            const snapshot = Object.fromEntries(Object.entries(files).map(([path, text]) => ["/project/" + path, text]));
            const shell = new Bash({files: snapshot, cwd: "/project", commands: [
                "cat", "cut", "echo", "grep", "head", "ls", "pwd", "sort", "tail", "tr", "uniq", "wc", "test", "printf"
            ], executionLimits: {maxCallDepth: 16, maxCommandCount: 500, maxLoopIterations: 1000,
                maxStringLength: 131072, maxOutputSize: 8192, maxArrayElements: 4096}});
            const run = await shell.exec(files[entry]);
            append(false, run.stdout);
            append(true, run.stderr);
            exitCode = run.exitCode;
        } else {
            for (const method of ["log", "info", "warn", "error", "debug"]) {
                console[method] = (...values) => append(method === "warn" || method === "error",
                    values.map(value => typeof value === "object" ? JSON.stringify(value) : String(value)).join(" ") + "\n");
            }
            const modulePath = entry.split("/").map(encodeURIComponent).join("/");
            await import("/project/" + modulePath);
        }
    } catch (error) {
        exitCode = 1;
        append(true, String(error));
    }
    port.postMessage({exitCode, stdout, stderr, truncated});
    port.close();
    self.close();
};
