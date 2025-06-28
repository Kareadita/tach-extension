import json
import os
import sys
from typing import NoReturn


def main() -> NoReturn:
    _, _, build_type = sys.argv

    # Always build only src:all:kavita
    modules = [":src:all:kavita"]
    deleted = ["all.kavita"]

    chunked = {
        "chunk": [
            {
                "number": 1,
                "modules": [f":src:all:kavita:assemble{build_type}"]
            }
        ]
    }

    print(
        f"Module chunks to build:\n{json.dumps(chunked, indent=2)}\n\nModule to delete:\n{json.dumps(deleted, indent=2)}")

    if os.getenv("CI") == "true":
        with open(os.getenv("GITHUB_OUTPUT"), 'a') as out_file:
            out_file.write(f"matrix={json.dumps(chunked)}\n")
            out_file.write(f"delete={json.dumps(deleted)}\n")


if __name__ == '__main__':
    main()
