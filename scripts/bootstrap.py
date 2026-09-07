"""Fetch pinned build inputs. No SDK license acceptance or model download."""
import argparse
import hashlib
import io
import json
import pathlib
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]


def download(asset):
    data = urllib.request.urlopen(asset["url"], timeout=120).read()
    if hashlib.sha256(data).hexdigest() != asset["sha256"]:
        raise ValueError("Checksum mismatch: " + asset["id"])
    return data


parser = argparse.ArgumentParser()
parser.add_argument("target", choices=["web", "android"])
args = parser.parse_args()
if args.target == "web":
    asset = next(a for a in json.loads((ROOT / "contracts/models.json").read_text())["assets"] if a["id"] == "vad")
    path = ROOT / "web/public/runtime/vad.onnx"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(download(asset))
else:
    for asset in json.loads((ROOT / "contracts/native-runtime.json").read_text()):
        data = download(asset)
        if asset["id"] == "sherpa":
            path = ROOT / "android/app/libs/sherpa-onnx-1.13.7.aar"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        else:
            target = ROOT / "android/vendor/llama.cpp"
            target.mkdir(parents=True, exist_ok=True)
            with tarfile.open(fileobj=io.BytesIO(data)) as archive:
                for member in archive.getmembers():
                    name = pathlib.PurePosixPath(member.name)
                    if len(name.parts) < 2:
                        continue
                    member.name = str(pathlib.PurePosixPath(*name.parts[1:]))
                    archive.extract(member, target, filter="data")
