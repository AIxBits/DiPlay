"""Fetch pinned public JVM dependencies, verifying every SHA256 before use."""
from pathlib import Path
import hashlib
import json
import os
import urllib.request

root = Path(__file__).resolve().parents[2]
target = Path(os.environ.get("DIPLAY_DEPS_DIR", root / "downloads"))
target.mkdir(parents=True, exist_ok=True)
hashes = json.loads(Path(__file__).with_name("dependency-sha256.json").read_text())
artifacts = {
    "bctls-jdk15to18-1.79.jar": "org/bouncycastle/bctls-jdk15to18/1.79",
    "bcutil-jdk15to18-1.79.jar": "org/bouncycastle/bcutil-jdk15to18/1.79",
    "bcprov-jdk15to18-1.79.jar": "org/bouncycastle/bcprov-jdk15to18/1.79",
    "jmdns-3.5.9.jar": "org/jmdns/jmdns/3.5.9",
    "slf4j-api-1.7.36.jar": "org/slf4j/slf4j-api/1.7.36",
    "slf4j-nop-1.7.36.jar": "org/slf4j/slf4j-nop/1.7.36",
    "junit-4.13.2.jar": "junit/junit/4.13.2",
    "hamcrest-core-1.3.jar": "org/hamcrest/hamcrest-core/1.3",
}
for name, path in artifacts.items():
    destination = target / name
    if destination.is_file():
        data = destination.read_bytes()
    else:
        with urllib.request.urlopen("https://repo.maven.apache.org/maven2/" + path + "/" + name, timeout=60) as response:
            data = response.read()
    if hashlib.sha256(data).hexdigest() != hashes[name]:
        raise SystemExit("Dependency hash mismatch: " + name)
    if not destination.exists():
        destination.write_bytes(data)
    print("Verified: " + name)
