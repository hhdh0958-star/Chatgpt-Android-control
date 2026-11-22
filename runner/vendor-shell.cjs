// Run with node vendor-shell.cjs <directory containing node_modules and package-lock.json>.
// Development only. The signed Android app loads its build-time browser bundle offline.
const fs = require("node:fs");
const path = require("node:path");
const base = path.resolve(process.argv[2]);
const output = path.join(__dirname, "assets", "python");
const lock = JSON.parse(fs.readFileSync(path.join(base, "package-lock.json")));
const esbuild = require(path.join(base, "node_modules/esbuild"));
if (lock.packages["node_modules/just-bash"].version !== "3.6.1" || esbuild.version !== "0.25.12") {
    throw Error("Expected just-bash 3.6.1 and esbuild 0.25.12");
}

async function build() {
    const result = await esbuild.build({absWorkingDir: base,
        stdin: {contents: "export {Bash} from 'just-bash/browser';", resolveDir: base},
        outfile: path.join(output, "shell-runtime.js"), platform: "browser", format: "esm",
        bundle: true, minify: true, legalComments: "inline", metafile: true,
        plugins: [{name: "no-native-compression", setup(build) {
            build.onResolve({filter: /^node:zlib$/}, () => ({path: "zlib", namespace: "disabled"}));
            build.onLoad({filter: /.*/, namespace: "disabled"}, () => ({contents:
                "export const constants={}; export function gzipSync(){throw Error('Unavailable')}; export const gunzipSync=gzipSync;"}));
        }}]});
    const packages = new Set(["node_modules/just-bash"]);
    for (const file of Object.keys(result.metafile.inputs)) {
        if (!file.startsWith("node_modules/")) continue;
        let directory = path.dirname(file);
        while (directory !== "." && !lock.packages[directory.replaceAll("\\", "/")]) directory = path.dirname(directory);
        if (directory !== ".") packages.add(directory.replaceAll("\\", "/"));
    }
    // Embedded dependency notices found in the upstream prebundled browser entry.
    for (const name of ["ieee754", "re2js"]) if (lock.packages["node_modules/" + name]) packages.add("node_modules/" + name);
    const notices = [];
    for (const directory of [...packages].sort()) {
        const dep = lock.packages[directory];
        notices.push(directory + " " + dep.version + "\n" + dep.resolved + "\n" + dep.integrity);
        const packageDir = path.join(base, directory);
        const files = fs.readdirSync(packageDir).filter(name => /^(licen[sc]e|copying|notice)([.-].*)?$/i.test(name));
        if (!files.length) throw Error("Missing notices for " + directory);
        for (const file of files) notices.push(fs.readFileSync(path.join(packageDir, file), "utf8"));
    }
    fs.writeFileSync(path.join(output, "LICENSE-shell-runtime.txt"), notices.join("\n\n"));
}
build().catch(error => { console.error(error); process.exitCode = 1; });
