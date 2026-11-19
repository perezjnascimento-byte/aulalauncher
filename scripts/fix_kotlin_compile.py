from pathlib import Path

project_dir = Path(Path(".aura_project_dir").read_text(encoding="utf-8").strip())
source = project_dir / "app/src/main/java/com/aura/launcher/MainActivity.kt"
lines = source.read_text(encoding="utf-8").splitlines()

# Kotlin regular strings require backslashes to be escaped. Use a raw string
# for the IPv4-address regex so the regex escapes reach java.util.regex intact.
old_regex = r'Regex("^\d+\.\d+\.\d+\.\d+$")'
new_regex = 'Regex("""^\\d+\\.\\d+\\.\\d+\\.\\d+$""")'
regex_matches = [i for i, line in enumerate(lines) if old_regex in line]
if len(regex_matches) != 1:
    raise SystemExit(f"Expected one IPv4 regex to fix; found {len(regex_matches)}")
lines[regex_matches[0]] = lines[regex_matches[0]].replace(old_regex, new_regex)

# A `while (true)` expression has Kotlin type Unit even when its branches
# return from the Callable. Add a defensive String return after the loop so
# Callable<String> has a valid result if the loop ever terminates normally.
callable_start = next(
    (i for i, line in enumerate(lines) if "Callable<String> {" in line), None
)
if callable_start is None:
    raise SystemExit("Could not find the HTTP Callable<String> block")
loop_start = next(
    (i for i in range(callable_start, len(lines)) if lines[i].strip() == "while (true) {"),
    None,
)
if loop_start is None:
    raise SystemExit("Could not find the HTTP redirect loop")
indent = lines[loop_start][: len(lines[loop_start]) - len(lines[loop_start].lstrip())]
loop_end = next(
    (i for i in range(loop_start + 1, len(lines)) if lines[i] == indent + "}"),
    None,
)
if loop_end is None:
    raise SystemExit("Could not find the end of the HTTP redirect loop")
if loop_end + 1 < len(lines) and "return@Callable JSONObject().put(\"status\", 0)" in lines[loop_end + 1]:
    raise SystemExit("HTTP Callable fallback already exists; refusing to patch twice")
lines.insert(
    loop_end + 1,
    indent + 'return@Callable JSONObject().put("status", 0).put("body", "").toString()',
)
source.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"Patched Kotlin build errors in {source}")
