"""Publish a Sofia APK to the SM-T820's own channel; manifest last. Refuses the phone channel. Needs an owner go.

usage: publish-tablet-channel.py APK TABLET_FOLDER_ID VERSION_CODE VERSION_NAME
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEVICE = 'SM-T820'
BASE = 'https://www.googleapis.com/drive/v3/'
TOKEN = subprocess.check_output(['gcloud', 'auth', 'print-access-token'], stderr=subprocess.DEVNULL, text=True).strip()


def raw(path):
    return urllib.request.urlopen(urllib.request.Request(BASE + path, headers={'Authorization': 'Bearer ' + TOKEN}), timeout=180).read()


def listing(parent, name):
    query = "'" + parent + "' in parents and name = '" + name + "' and trashed = false"
    return json.loads(raw('files?' + urllib.parse.urlencode({'q': query, 'fields': 'files(id)', 'orderBy': 'modifiedTime desc'})))['files']


def upload(name, parent, content):
    boundary = 'sofia-' + uuid.uuid4().hex
    body = (b'--' + boundary.encode() + b'\r\nContent-Type: application/json\r\n\r\n' + json.dumps({'name': name, 'parents': [parent]}).encode() +
            b'\r\n--' + boundary.encode() + b'\r\nContent-Type: application/octet-stream\r\n\r\n' + content + b'\r\n--' + boundary.encode() + b'--\r\n')
    request = urllib.request.Request('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,md5Checksum', data=body,
                                     headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'multipart/related; boundary=' + boundary}, method='POST')
    result = json.loads(urllib.request.urlopen(request, timeout=180).read())
    assert result['md5Checksum'] == hashlib.md5(content).hexdigest()


def main():
    apk, folder, code, name = pathlib.Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4]
    phone = json.loads((ROOT / 'app/src/main/assets/private-channel.json').read_text())['folder']
    assert folder != phone, 'That is the phone channel: never publish a tablet build there'
    badging = subprocess.run([str(pathlib.Path.home() / 'Library/Android/sdk/build-tools/36.0.0/aapt'), 'dump', 'badging', str(apk)], capture_output=True, text=True).stdout
    assert "name='com.tailorshop.counter' versionCode='%d' versionName='%s'" % (code, name) in badging
    parts = listing(folder, 'parts')
    assert len(parts) == 1, 'The tablet channel needs exactly one parts folder'
    data = apk.read_bytes()
    chunks = [data[i:i + 4 * 1024 * 1024] for i in range(0, len(data), 4 * 1024 * 1024)]
    names = ['t%d.part%c%c' % (code, 97 + i // 26, 97 + i % 26) for i in range(len(chunks))]
    for piece, chunk in zip(names, chunks):
        upload(piece, parts[0]['id'], chunk)
    manifest = {'package': 'com.tailorshop.counter', 'device': DEVICE, 'versionCode': code, 'versionName': name, 'size': len(data),
                'sha256': hashlib.sha256(data).hexdigest(), 'parts': names, 'partSha256': [hashlib.sha256(c).hexdigest() for c in chunks]}
    upload('latest.json', folder, json.dumps(manifest).encode())
    print(json.dumps({k: manifest[k] for k in ('device', 'versionCode', 'versionName', 'size', 'sha256')}))


if __name__ == '__main__':
    main()
