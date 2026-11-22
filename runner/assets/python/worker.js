"use strict";

importScripts("pyodide.js");

function outputStream() {
    const bytes = new Uint8Array(2048);
    let offset = 0;
    let truncated = false;
    return {
        write(chunk) {
            const count = Math.min(chunk.byteLength, bytes.byteLength - offset);
            bytes.set(chunk.subarray(0, count), offset);
            offset += count;
            truncated ||= count < chunk.byteLength;
            return chunk.byteLength;
        },
        text: () => new TextDecoder().decode(bytes.subarray(0, offset)),
        truncated: () => truncated
    };
}

self.onmessage = async event => {
    self.onmessage = null;
    const port = event.ports[0];
    const {entry, files} = event.data;
    const stdout = outputStream(), stderr = outputStream();
    const decoder = new TextEncoder();
    let exitCode = -1;
    try {
        const python = await loadPyodide({
            indexURL: new URL("./", location.href).href,
            fullStdLib: false,
            stdin: () => undefined
        });
        python.setStdout({write: stdout.write});
        python.setStderr({write: stderr.write});
        // This task uses a single worker. Also deny nested constructors synchronously
        // on Chromium versions where the worker-src violation arrives as an async error.
        for (const name of ["Worker", "SharedWorker"]) {
            Object.defineProperty(self, name, {value: undefined, writable: false, configurable: false});
        }
        const root = "/project";
        python.FS.mkdir(root);
        for (const [path, content] of Object.entries(files)) {
            if (!path || path.split("/").some(s => !s || s === "." || s === "..") || path.includes("\\")) {
                throw new Error("Invalid project path");
            }
            const full = root + "/" + path;
            python.FS.mkdirTree(full.slice(0, full.lastIndexOf("/")));
            python.FS.writeFile(full, decoder.encode(content));
        }
        python.FS.chdir(root);
        python.globals.set("_project_entry", entry);
        exitCode = python.runPython(`
import os, runpy, sys, traceback
sys.path[:0] = [os.path.dirname(os.path.abspath(_project_entry)), os.getcwd()]
sys.argv[:] = [_project_entry]
try:
    runpy.run_path(_project_entry, run_name="__main__")
    _project_exit = 0
except SystemExit as exc:
    _project_exit = exc.code
    if _project_exit is None:
        _project_exit = 0
    elif not isinstance(_project_exit, int):
        print(_project_exit, file=sys.stderr)
        _project_exit = 1
except BaseException:
    traceback.print_exc()
    _project_exit = 1
sys.stdout.flush()
sys.stderr.flush()
int(_project_exit) % 256
`);
    } catch (error) {
        stderr.write(decoder.encode(String(error).slice(0, 2048)));
    }
    port.postMessage({exitCode, stdout: stdout.text(), stderr: stderr.text(),
        truncated: stdout.truncated() || stderr.truncated()});
    port.close();
    self.close();
};
