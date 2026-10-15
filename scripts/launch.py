"""Open the GUI once the local server is ready; no shell environment setup needed."""
from pathlib import Path
import subprocess
import sys
import threading
import time
import urllib.request
import webbrowser


def open_when_ready():
    for _ in range(60):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8000/api/status', timeout=1) as response:
                if response.status == 200:
                    webbrowser.open('http://127.0.0.1:8000')
                    return
        except OSError:
            pass
        time.sleep(.5)


if __name__ == '__main__':
    threading.Thread(target=open_when_ready, daemon=True).start()
    try:
        raise SystemExit(subprocess.call([sys.executable, '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8000'], cwd=Path(__file__).resolve().parent.parent))
    except KeyboardInterrupt:
        pass
