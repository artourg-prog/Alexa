"""Owner-authorized release to the existing private Wi-Fi channel; manifest is uploaded last."""
import concurrent.futures
import hashlib
import json
import pathlib
import subprocess
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
APK = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
OUT = ROOT.parent / 'audit/touch-release'
OUT.mkdir(parents=True, exist_ok=True)
TOKEN = subprocess.check_output(['gcloud', 'auth', 'print-access-token'], stderr=subprocess.DEVNULL, text=True).strip()
FOLDER = json.loads((ROOT / 'app/src/main/assets/private-channel.json').read_text())['folder']
BASE = 'https://www.googleapis.com/drive/v3/'


def raw(path):
    return urllib.request.urlopen(urllib.request.Request(BASE + path, headers={'Authorization': 'Bearer ' + TOKEN}), timeout=120).read()


def listing(parent, name):
    query = "'" + parent + "' in parents and name = '" + name + "' and trashed = false"
    return json.loads(raw('files?' + urllib.parse.urlencode({'q': query, 'fields': 'files(id,name)', 'orderBy': 'modifiedTime desc', 'pageSize': 1000})))['files']


def latest():
    files = listing(FOLDER, 'latest.json')
    if not files:
        raise RuntimeError('Existing channel manifest missing')
    return files[0]['id'], json.loads(raw('files/' + files[0]['id'] + '?alt=media'))


def upload(name, parent, content):
    boundary = 'sofia-' + uuid.uuid4().hex
    metadata = json.dumps({'name': name, 'parents': [parent]}).encode()
    body = (b'--' + boundary.encode() + b'\r\nContent-Type: application/json\r\n\r\n' + metadata +
            b'\r\n--' + boundary.encode() + b'\r\nContent-Type: application/octet-stream\r\n\r\n' + content +
            b'\r\n--' + boundary.encode() + b'--\r\n')
    req = urllib.request.Request('https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,size,md5Checksum',
                                 data=body, headers={'Authorization': 'Bearer ' + TOKEN, 'Content-Type': 'multipart/related; boundary=' + boundary}, method='POST')
    result = json.loads(urllib.request.urlopen(req, timeout=180).read())
    assert int(result['size']) == len(content)
    assert result['md5Checksum'] == hashlib.md5(content).hexdigest()
    return result['id']


def main():
    before_id, before = latest()
    assert before['package'] == 'com.tailorshop.counter' and before['versionCode'] == 47, 'Channel changed; review before publishing'
    parts = listing(FOLDER, 'parts')
    assert len(parts) == 1, 'Ambiguous parts folder'
    data = APK.read_bytes()
    badging = subprocess.run([str(pathlib.Path.home() / 'Library/Android/sdk/build-tools/36.0.0/aapt'), 'dump', 'badging', str(APK)], capture_output=True, text=True).stdout
    assert "name='com.tailorshop.counter' versionCode='48' versionName='0.48'" in badging
    chunks = [data[i:i + 4 * 1024 * 1024] for i in range(0, len(data), 4 * 1024 * 1024)]
    names = ['p48.part' + chr(97 + i // 26) + chr(97 + i % 26) for i in range(len(chunks))]
    manifest = {'package': 'com.tailorshop.counter', 'versionCode': 48, 'versionName': '0.48', 'size': len(data),
                'sha256': hashlib.sha256(data).hexdigest(), 'parts': names,
                'partSha256': [hashlib.sha256(chunk).hexdigest() for chunk in chunks]}
    print('Uploading and verifying', len(chunks), 'pieces; existing 0.47 remains active until completion.', flush=True)

    def piece(i):
        existing = listing(parts[0]['id'], names[i])
        if existing and hashlib.sha256(raw('files/' + existing[0]['id'] + '?alt=media')).hexdigest() == manifest['partSha256'][i]:
            file_id = existing[0]['id']
        else:
            file_id = upload(names[i], parts[0]['id'], chunks[i])
        received = raw('files/' + file_id + '?alt=media')
        assert hashlib.sha256(received).hexdigest() == manifest['partSha256'][i]
        print('Verified piece', i + 1, '/', len(chunks), flush=True)
        return file_id

    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        list(pool.map(piece, range(len(chunks))))
    assert latest()[0] == before_id, 'Channel changed during preparation; manifest not published'
    manifest_id = upload('latest.json', FOLDER, json.dumps(manifest).encode())
    remote_id, remote = latest()
    assert remote_id == manifest_id and remote == manifest
    summary = dict(manifest, all_remote_pieces_verified=True, published=True, previous_version=47)
    (OUT / 'publication.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps({'published': True, 'version': '0.48', 'parts': len(chunks), 'size': len(data), 'sha256': manifest['sha256'], 'all_remote_pieces_verified': True}), flush=True)


if __name__ == '__main__':
    main()
