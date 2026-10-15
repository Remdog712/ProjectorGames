"""Fetch pinned Java dependencies from Maven Central with SHA-256 verification."""
import hashlib
from pathlib import Path
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
CODE = ROOT / 'processing' / 'ProjectorCalibrator' / 'code'
DEPENDENCIES = [
    ('net/java/dev/jna/jna/5.15.0/jna-5.15.0.jar', 'a564158d28ab5127fc6a958028ed54279fe0999662c46425b6a3b09a2a52094d'),
    ('org/openpnp/opencv/4.9.0-0/opencv-4.9.0-0.jar', '17823d249cb8ba18bc3cdb878db84936694d1cfaeac0f95517e320c8a8c458d9'),
]
CORE = ('org/processing/core/4.3.1/core-4.3.1.jar', '245f454ceb89c2cd03bc9b1ea85159769495279af8293ffab688f330c6a80ce1')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch(relative, expected, directory):
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / relative.split('/')[-1]
    if target.exists() and digest(target) == expected:
        print('Verified:', target.name)
        return target
    temporary = target.with_suffix('.download')
    print('Downloading:', target.name, flush=True)
    try:
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/' + relative, timeout=120) as response, temporary.open('wb') as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        if digest(temporary) != expected:
            raise RuntimeError('SHA-256 mismatch for ' + target.name + '; file was not installed.')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    return target


if __name__ == '__main__':
    for relative, expected in DEPENDENCIES:
        fetch(relative, expected, CODE)
    if '--with-core' in sys.argv:
        fetch(*CORE, ROOT / '.processing-build')
    print('Ready. Open processing/ProjectorCalibrator/ProjectorCalibrator.pde in Processing 4 (Java mode).')
