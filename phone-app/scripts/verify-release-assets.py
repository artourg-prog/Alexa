"""Reject incomplete update APKs before publishing; compare private assets to a known-good APK."""
import argparse
import hashlib
import zipfile

REQUIRED = (
    "assets/private-channel.json",
    "assets/voice-models/whisper-base-q5_1.bin",
    "assets/voice-models/ru.zip",
    "assets/voice-models/en.zip",
)


def verify(baseline, candidate):
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(candidate) as new:
        for name in REQUIRED:
            if name not in new.namelist():
                raise ValueError("Required release asset missing: " + name)
            if hashlib.sha256(old.read(name)).digest() != hashlib.sha256(new.read(name)).digest():
                raise ValueError("Release asset changed; review explicitly before publishing: " + name)
    return len(REQUIRED)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_apk")
    parser.add_argument("candidate_apk")
    args = parser.parse_args()
    print("Verified required release assets against baseline:", verify(args.baseline_apk, args.candidate_apk))
