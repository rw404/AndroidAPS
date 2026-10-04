"""Download pinned test dependencies for the reduced JVM verification harness.

Run with ordinary network authorization and the environment proxy/TLS settings.
Kotlin/Guava/annotation dependencies come from the specified Gradle 9.0.0 lib folder.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import urlretrieve

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--work-dir', type=Path, default=Path('/tmp/aaps-verification'))
args = parser.parse_args()
DEST = args.work_dir / 'jars'
DEST.mkdir(parents=True, exist_ok=True)
ARTIFACTS = [
    ('org.junit.platform', 'junit-platform-console-standalone', '6.0.1'),
    ('com.google.truth', 'truth', '1.4.5'),
    ('org.mockito', 'mockito-core', '5.21.0'),
    ('org.mockito', 'mockito-junit-jupiter', '5.21.0'),
    ('org.mockito.kotlin', 'mockito-kotlin', '6.1.0'),
    ('net.bytebuddy', 'byte-buddy', '1.17.8'),
    ('net.bytebuddy', 'byte-buddy-agent', '1.17.8'),
    ('org.objenesis', 'objenesis', '3.3'),
    ('javax.inject', 'javax.inject', '1'),
]

def download(artifact):
    group, name, version = artifact
    filename = f'{name}-{version}.jar'
    path = DEST / filename
    if not path.exists():
        url = f'https://repo.maven.apache.org/maven2/{group.replace(".", "/")}/{name}/{version}/{filename}'
        urlretrieve(url, path)
    return f'{filename}: {path.stat().st_size} bytes'

with ThreadPoolExecutor(max_workers=4) as executor:
    for result in executor.map(download, ARTIFACTS):
        print(result)
