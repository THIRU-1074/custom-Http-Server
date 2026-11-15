#!/usr/bin/env python3
"""Token-size guard for llms.txt and the Jolt todo benchmark app (sources + pom.xml).

Tokenizer: tiktoken cl100k_base; falls back to len(text)/4, clearly labelled as an estimate.
Fails (exit 1) if llms.txt exceeds 2100 tokens or the app total grows more than 5% over
benchmarks/guard/token-baseline.json. Baselines made with a different tokenizer are not compared.

  python benchmarks/guard/token-guard.py
  python benchmarks/guard/token-guard.py --write-baseline   # after an intended, reviewed increase
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APP = ROOT / "benchmarks/tokens/jolt"
BASELINE = HERE / "token-baseline.json"
LLMS_LIMIT = 2100
APP_GROWTH = 0.05


def load_tokenizer():
    try:
        import tiktoken

        enc = tiktoken.get_encoding("cl100k_base")
        return "tiktoken cl100k_base", lambda s: len(enc.encode(s))
    except Exception as e:
        return (f"ESTIMATE chars/4 (tiktoken unavailable: {type(e).__name__})",
                lambda s: -(-len(s) // 4))


def app_files():
    return sorted((APP / "src/main").rglob("*.java")) + [APP / "pom.xml"]


def main():
    name, count = load_tokenizer()
    real = name.startswith("tiktoken")
    llms = count((ROOT / "llms.txt").read_text(encoding="utf-8"))
    parts = {str(p.relative_to(APP)).replace("\\", "/"): count(p.read_text(encoding="utf-8"))
             for p in app_files()}
    app = sum(parts.values())
    print(f"Tokenizer: {name}")
    print(f"llms.txt: {llms} tokens (limit {LLMS_LIMIT})")
    for k, v in parts.items():
        print(f"  {k}: {v}")
    print(f"jolt todo app total: {app} tokens")

    if "--write-baseline" in sys.argv:
        BASELINE.write_text(json.dumps(
            {"tokenizer": name, "llms_txt": llms, "jolt_app_total": app, "jolt_app_files": parts},
            indent=2) + "\n")
        print("wrote token-baseline.json")
        return 0

    failed = False
    if llms > LLMS_LIMIT:
        print(f"FAIL: llms.txt is {llms} tokens, over the {LLMS_LIMIT} limit. "
              "Tighten the text; do not raise the limit.")
        failed = True
    base = json.loads(BASELINE.read_text())
    if base["tokenizer"] != name:
        print(f"WARNING: baseline tokenizer '{base['tokenizer']}' differs; app growth not compared.")
    else:
        limit = base["jolt_app_total"] * (1 + APP_GROWTH)
        growth = app / base["jolt_app_total"] - 1
        print(f"app vs baseline {base['jolt_app_total']}: {growth:+.1%} (limit +{APP_GROWTH:.0%})")
        if app > limit:
            print("FAIL: Jolt todo app grew more than 5% over baseline. Keep the API small, or "
                  "refresh the baseline in a reviewed commit if the growth is intended.")
            failed = True
    if not real:
        print("NOTE: estimate only; install tiktoken for authoritative counts.")
    print("FAIL" if failed else "OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
