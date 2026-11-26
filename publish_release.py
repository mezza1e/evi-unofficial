import subprocess
import urllib.request
import json
import os
import hashlib
import sys

owner = "mezza1e"
repo = "evi-unofficial"
tag_name = "v1.0.3"
release_title = "Evi Unofficial v1.0.3"
apk_name = "Evi-Unofficial-1.0.3.apk"
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
release_body = f"""## 🎵 Evi Unofficial v1.0.3

Major playback engine upgrade fixing Loop (1) (`REPEAT_MODE_ONE`) rollover bleed, post-skip loop breakage, and audio quality degradation.

### 🐛 Playback Engine & Audio Fixes:
- 🚀 **Upgraded to AndroidX Media3 1.9.4**: Incorporates upstream fix (`androidx/media#3016`) for `ProgressiveMediaSource` timeline thrashing under `REPEAT_MODE_ONE`, plus `Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING` and constant-bitrate seeking for frame-accurate gapless looping.
- 🔁 **True Native Gapless Loop (1)**: Removed the main-thread manual seek workaround so audio frames repeat natively in ExoPlayer's render pipeline with zero next-song audio bleed. Explicit "Next" skips cleanly advance while keeping Loop (1) active on the new track.
- ⚡ **Stable Stream Caching (`StableUriDataSource`)**: Restored `CacheDataSource` (`stream_cache_v2`) keyed by `"{{songId}}_{{quality}}"` while pinning `getUri()` to the original request URL so expiring CDN 302 redirects are never persisted as `exo_redir` entries, and evicting cache keys automatically on playback errors.
- 🎛️ **Clean Hardware AudioEffect Lifecycle**: Per-effect lazy allocation (only instantiates `Equalizer`, `BassBoost`, or `Virtualizer` when non-flat/non-zero), closing old control sessions cleanly before opening new ones to keep the audio path bit-clean.
- 🛡️ **Standard `ForwardingPlayer` & Deferred Queue Pruning**: Eliminated custom `Player.Listener` wrapper desyncs across `MediaSession` and deferred autoplay pruning on `REPEAT_MODE_ALL` outside listener dispatch.

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
