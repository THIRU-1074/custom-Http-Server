#!/usr/bin/env python3
"""Counts tokens in each framework's source, test and build config; prints a table.

Tokenizer: tiktoken cl100k_base when importable and its vocabulary loads,
otherwise a clearly labelled estimate of len(text) / 4.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).parent
FRAMEWORKS = ["jolt", "spring", "javalin", "express"]
SUFFIXES = {".java", ".js", ".xml", ".json"}
SKIP_DIRS = {"node_modules", "target"}


def load_tokenizer():
    try:
        import tiktoken

        enc = tiktoken.get_encoding("cl100k_base")
        return "tiktoken cl100k_base", lambda s: len(enc.encode(s))
    except Exception as e:  # ImportError, or vocabulary download failure
        return f"ESTIMATE chars/4 (tiktoken unavailable: {type(e).__name__})", lambda s: -(-len(s) // 4)


def files(framework):
    base = ROOT / framework
    return sorted(
        p
        for p in base.rglob("*")
        if p.is_file()
        and p.suffix in SUFFIXES
        and not SKIP_DIRS & set(p.relative_to(base).parts)
        and p.name != "package-lock.json"
    )


def main():
    name, count = load_tokenizer()
    detail = "-v" in sys.argv
    rows = []
    for fw in FRAMEWORKS:
        total = {"src": 0, "test": 0, "build": 0}
        for p in files(fw):
            text = p.read_text(encoding="utf-8")
            n = count(text)
            kind = "build" if p.name in {"pom.xml", "package.json"} else "test" if "test" in p.name.lower() else "src"
            total[kind] += n
            if detail:
                print(f"  {fw}/{p.relative_to(ROOT / fw)}: {n}")
        rows.append((fw, total["src"], total["test"], total["build"], sum(total.values())))

    print(f"Tokenizer: {name}\n")
    print(f"{'framework':<10}{'source':>8}{'test':>8}{'build':>8}{'total':>8}{'vs jolt':>10}")
    base = rows[0][4]
    for fw, s, t, b, tot in rows:
        print(f"{fw:<10}{s:>8}{t:>8}{b:>8}{tot:>8}{tot / base:>9.2f}x")


if __name__ == "__main__":
    main()
