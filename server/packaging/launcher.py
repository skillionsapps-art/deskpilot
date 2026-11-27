"""PyInstaller entry point (the package uses relative imports, so it can't be the script itself)."""
from deskpilot_server.__main__ import main

if __name__ == "__main__":
    main()
