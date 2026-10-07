#!/usr/bin/env python3
"""Sorts the libraries listed under each license in THIRD_PARTY_LICENSES.md.

cargo-about lists them in an order that can differ from one run to the next, which would make
`scripts/update-licenses.sh --check` fail on a file that is right. Sorted, the same Cargo.lock gives the same file
on any machine.

    scripts/sort-license-users.py <file>
"""
import re
import sys


def main(path):
    with open(path, encoding="utf-8") as file:
        text = file.read()
    text = re.sub(
        r"(Used by:\n\n)((?:- .*\n)+)",
        lambda match: match.group(1) + "\n".join(sorted(match.group(2).splitlines())) + "\n",
        text,
    )
    with open(path, "w", encoding="utf-8") as file:
        file.write(text)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
