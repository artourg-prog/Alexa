#!/usr/bin/env python3
"""Pinned official whisper.cpp source/model. No runtime keys or recordings belong here."""
from pathlib import Path
from urllib.request import urlopen
import hashlib
import tarfile
import shutil
root=Path(__file__).resolve().parents[1]

def download(url,target,expected):
    if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest()==expected:return
    with urlopen(url,timeout=120) as response:data=response.read()
    assert hashlib.sha256(data).hexdigest()==expected,"Unexpected download checksum"
    target.parent.mkdir(parents=True,exist_ok=True)
    tmp=target.with_suffix('.tmp');tmp.write_bytes(data);tmp.replace(target)

model=root/'app/src/main/assets/voice-models/whisper-base-q5_1.bin'
download('https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin',model,
 '422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898')
vendor=root/'native/third_party';vendor.mkdir(parents=True,exist_ok=True)
archive=vendor/'whisper-v1.9.5.tar.gz'
download('https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz',archive,
 'ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720')
source=vendor/'whisper.cpp'
if not (source/'include/whisper.h').is_file():
    unpack=vendor/'unpack';unpack.mkdir(exist_ok=True)
    with tarfile.open(archive) as tf:
        for member in tf.getmembers():
            assert (unpack/member.name).resolve().is_relative_to(unpack.resolve()),"Invalid source archive"
        tf.extractall(unpack)
    extracted=unpack/'whisper.cpp-1.9.5'
    extracted.rename(source);shutil.rmtree(unpack)
print('Pinned Whisper source and local model verified')
