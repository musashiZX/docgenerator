import esbuild from "esbuild";

await esbuild.build({
  entryPoints: ["src/block-editor.js"],
  bundle: true,
  format: "iife",
  minify: true,
  target: ["es2020"],
  outfile: "../src/main/resources/static/block-editor.bundle.js",
});

console.log("Built block-editor.bundle.js");
