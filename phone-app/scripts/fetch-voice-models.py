#!/usr/bin/env python3
"""Reproducible official Apache-2.0 model assets. Never download production data."""
from pathlib import Path
from urllib.request import urlopen
import hashlib
import zipfile
models = {
    "en": ("vosk-model-small-en-us-0.15", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
    "ru": ("vosk-model-small-ru-0.22", "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"),
}
root = Path(__file__).resolve().parents[1] / "app/src/main/assets/voice-models"
root.mkdir(parents=True, exist_ok=True)
for language, (name, expected) in models.items():
    target = root / (language + ".zip")
    if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
        with urlopen("https://alphacephei.com/vosk/models/" + name + ".zip", timeout=90) as response:
            data = response.read()
        assert hashlib.sha256(data).hexdigest() == expected, "Unexpected model checksum"
        tmp = target.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.replace(target)
    with zipfile.ZipFile(target) as archive:
        assert archive.testzip() is None
    print(language, "verified", target.stat().st_size)
