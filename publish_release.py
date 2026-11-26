import subprocess
import urllib.request
import json
import os
import hashlib
import sys

owner = "mezza1e"
repo = "evi-unofficial"
tag_name = "v1.0.2"
release_title = "Evi Unofficial v1.0.2"
apk_name = "Evi-Unofficial-1.0.2.apk"
apk_path = os.path.join(os.path.dirname(__file__), apk_name)

if not os.path.exists(apk_path):
    print(f"[ERROR] APK file not found at: {apk_path}")
    sys.exit(1)

# 1. Compute SHA-256
with open(apk_path, "rb") as f:
    apk_bytes = f.read()
sha256_hash = hashlib.sha256(apk_bytes).hexdigest().upper()
print(f"[*] Computed SHA-256: {sha256_hash}")

# 2. Get GitHub Token
token = os.environ.get("GITHUB_TOKEN")
if not token:
    try:
        p = subprocess.Popen(["git", "credential", "fill"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        out, _ = p.communicate(input="protocol=https\nhost=github.com\n")
        for line in out.splitlines():
            if line.startswith("password="):
                token = line.split("password=", 1)[1].strip()
                break
    except Exception as e:
        print(f"[!] Warning: Could not get token from git credential manager: {e}")

if not token:
    print("[ERROR] No GitHub token found. Please sign in or set GITHUB_TOKEN.")
    sys.exit(1)

headers = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.github+json",
    "User-Agent": "Evi-Release-Script",
    "X-GitHub-Api-Version": "2022-11-28"
}

# 3. Prepare Release Description
release_body = f"""## 🎵 Evi Unofficial v1.0.2

Critical playback engine update eliminating Loop (1) (`REPEAT_MODE_ONE`) stream glitches, cross-song retry interference, and stale disk cache corruption.

### 🐛 Playback Engine Fixes:
- 🔁 **Zero-Flush Service-Managed Loop (1) Engine**: Replaced ExoPlayer's internal `REPEAT_MODE_ONE` queue mutation (which tore down pre-buffered periods mid-song, forced `seekToCurrentPosition(true)` codec flushes, and opened duplicate concurrent stream connections on the same track URL) with a non-disruptive service-managed repeat controller. Enabling Loop (1) now has zero effect on the active audio buffer mid-song.
- 🧹 **Eliminated Stale Stream Cache (`SimpleCache`) Corruption**: Removed `CacheDataSource` wrapping on dynamic stream URLs (which persisted expired presigned redirect URLs and partial chunked spans across app restarts) and automatically purges legacy `media_cache` directories on launch.
- 🛡️ **Song-Scoped Error Retry Cancellation**: Delayed network retry coroutines are now strictly scoped to the failing track ID and cancelled immediately on track change, preventing failed track retries from seeking or interrupting other songs.
- 🎛️ **Bit-Perfect Flat EQ Bypass**: Bypasses OEM `AudioEffect` instances when Equalizer is flat with zero boost/virtualizer, and never falls back to deprecated global audio session `0`.
- 🖼️ **Offline Artwork & Integrity Validation**: Added local `file://` artwork decoding in `OkHttpBitmapLoader` and `Content-Type` validation in `DownloadManager`.

### 🔒 Security & Verification:
- Built and scanned via GitHub Actions (CodeQL passed with 0 vulnerabilities).
- **File**: `{apk_name}`
- **SHA-256 Checksum**:
  `{sha256_hash}`
"""

# 4. Check if release exists
print(f"[*] Checking if release {tag_name} exists...")
rel_url = f"https://api.github.com/repos/{owner}/{repo}/releases/tags/{tag_name}"
existing_release = None
try:
    req = urllib.request.Request(rel_url, headers=headers)
    with urllib.request.urlopen(req) as resp:
        existing_release = json.loads(resp.read().decode())
        print(f"[+] Found existing release ID: {existing_release['id']}")
except urllib.error.HTTPError as e:
    if e.code != 404:
        print(f"[ERROR] HTTP Error {e.code}: {e.read().decode()}")
        sys.exit(1)

if not existing_release:
    print(f"[*] Creating new release {tag_name}...")
    create_url = f"https://api.github.com/repos/{owner}/{repo}/releases"
    payload = json.dumps({
        "tag_name": tag_name,
        "target_commitish": "main",
        "name": release_title,
        "body": release_body,
        "draft": False,
        "prerelease": False
    }).encode("utf-8")
    req = urllib.request.Request(create_url, data=payload, headers={**headers, "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        release = json.loads(resp.read().decode())
        print(f"[+] Release created successfully (ID: {release['id']})")
else:
    release = existing_release
    # Update release body
    print(f"[*] Updating existing release description...")
    edit_url = f"https://api.github.com/repos/{owner}/{repo}/releases/{release['id']}"
    payload = json.dumps({
        "name": release_title,
        "body": release_body
    }).encode("utf-8")
    req = urllib.request.Request(edit_url, data=payload, headers={**headers, "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        release = json.loads(resp.read().decode())
        print("[+] Release description updated.")

release_id = release["id"]

# 5. Check if asset already exists and delete if present
for asset in release.get("assets", []):
    if asset["name"] == apk_name:
        print(f"[*] Deleting previous asset: {asset['name']} (ID: {asset['id']})...")
        del_req = urllib.request.Request(f"https://api.github.com/repos/{owner}/{repo}/releases/assets/{asset['id']}", headers=headers, method="DELETE")
        urllib.request.urlopen(del_req)
        print("[+] Deleted previous asset.")

# 6. Upload the APK asset
print(f"[*] Uploading {apk_name} ({len(apk_bytes):,} bytes)...")
upload_url = f"https://uploads.github.com/repos/{owner}/{repo}/releases/{release_id}/assets?name={apk_name}"
upload_headers = {
    **headers,
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": str(len(apk_bytes))
}
upload_req = urllib.request.Request(upload_url, data=apk_bytes, headers=upload_headers)
with urllib.request.urlopen(upload_req) as resp:
    asset_data = json.loads(resp.read().decode())
    print(f"[+] Asset uploaded successfully: {asset_data.get('browser_download_url')}")

print("\n" + "=" * 60)
print(f"🎉 SUCCESS! Release {tag_name} is published:")
print(f"👉 {release['html_url']}")
print("=" * 60)
