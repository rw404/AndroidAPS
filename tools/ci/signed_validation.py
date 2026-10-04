#!/usr/bin/env python3
"""Produce a signed validation artifact without exporting credentials or publishing a release."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

VARIANTS = {
    f'{flavor}{build_type}'
    for flavor in ('healfi', 'full', 'aapsclient', 'aapsclient2', 'pumpcontrol')
    for build_type in ('Release', 'Debug')
}
TEST_MODULES = ('ui', 'implementation', 'plugins/sync', 'pump/medtronic', 'pump/rileylink')
EXPECTED_PACKAGES = {
    'healfi': 'app.healfi.androidaps',
    'full': 'info.nightscout.androidaps',
    'aapsclient': 'info.nightscout.aapsclient',
    'aapsclient2': 'info.nightscout.aapsclient2',
    'pumpcontrol': 'info.nightscout.aapspumpcontrol',
}
REPO = Path.cwd()
TEMP = Path(os.environ['RUNNER_TEMP'])
SIGNING = TEMP / 'aaps-validation-signing'
ARTIFACT = TEMP / 'aaps-validation-artifact'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sha_file(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def mask(value):
    escaped = value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::add-mask::{escaped}', flush=True)


def decode64(value):
    return base64.b64decode(''.join(value.split()), validate=True)


def property_value(value):
    # java.util.Properties.load(InputStream) uses Latin-1; keep the file ASCII,
    # preserve whitespace and escape Unicode, including supplementary codepoints.
    result = []
    escapes = {'\\': '\\\\', ' ': '\\ ', '\n': '\\n', '\r': '\\r', '\t': '\\t', '\f': '\\f'}
    for char in value:
        if char in escapes:
            result.append(escapes[char])
        elif 32 <= ord(char) < 127:
            result.append(char)
        else:
            encoded = char.encode('utf-16-be')
            result.extend(f'\\u{int.from_bytes(encoded[i:i+2], "big"):04x}'
                          for i in range(0, len(encoded), 2))
    return ''.join(result)


def prepare():
    try:
        if os.environ.get('KEYSTORE_SET'):
            fields = decode64(os.environ['KEYSTORE_SET']).decode('utf-8').split('|')
            if len(fields) != 4:
                raise ValueError('Malformed key set')
        else:
            fields = [os.environ.get(key, '') for key in
                      ('KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD')]
        if not all(fields):
            raise ValueError('Missing signing field')
        for value in fields:
            mask(value)
        key_bytes = decode64(fields[0])
        if not key_bytes:
            raise ValueError('Empty keystore')
    except (ValueError, UnicodeError):
        raise SystemExit('Signing configuration is missing or invalid; no secret values were printed.')
    SIGNING.mkdir(mode=0o700, parents=True, exist_ok=False)
    os.chmod(SIGNING, 0o700)
    for filename, contents in (
        ('keystore.jks', key_bytes),
        ('credentials.json', json.dumps(dict(zip(('store_password', 'key_alias', 'key_password'), fields[1:]))).encode()),
    ):
        with (SIGNING / filename).open('xb') as stream:
            os.chmod(stream.name, 0o600)
            stream.write(contents)
    gradle_home = SIGNING / 'gradle'
    gradle_home.mkdir(mode=0o700)
    # Reuse downloaded dependencies without writing credentials to the ordinary
    # Gradle user home that setup-java caches. Only these two directories link out.
    ordinary_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    for directory in ('caches', 'wrapper'):
        target = ordinary_home / directory
        target.mkdir(parents=True, exist_ok=True)
        (gradle_home / directory).symlink_to(target, target_is_directory=True)
    properties = {
        'android.injected.signing.store.file': str(SIGNING / 'keystore.jks'),
        'android.injected.signing.store.password': fields[1],
        'android.injected.signing.key.alias': fields[2],
        'android.injected.signing.key.password': fields[3],
    }
    with (gradle_home / 'gradle.properties').open('x', encoding='ascii') as stream:
        os.chmod(stream.name, 0o600)
        stream.write(''.join(f'{key}={property_value(value)}\n' for key, value in properties.items()))
    print('Existing signing configuration prepared in private temporary files.')


def credentials():
    for filename in ('credentials.json', 'keystore.jks', 'gradle/gradle.properties'):
        if stat.S_IMODE((SIGNING / filename).stat().st_mode) != 0o600:
            raise SystemExit('Signing file permissions are not private.')
    return json.loads((SIGNING / 'credentials.json').read_text())


def variant():
    value = os.environ['AAPS_BUILD_VARIANT']
    if value not in VARIANTS:
        raise SystemExit('Unsupported build variant.')
    return value


def gradle(tasks, signed=False):
    command = [
        './gradlew', '-I', 'tools/verification/memory-limits.gradle',
        '-Dorg.gradle.jvmargs=-Xmx3g -XX:ActiveProcessorCount=2 -XX:+UseParallelGC -Xss1024m',
        '-Pkotlin.compiler.execution.strategy=in-process', '-Pksp.incremental=false',
        '--no-parallel', '--max-workers=2', '--no-daemon', '--console=plain',
        '--no-build-cache', '--no-configuration-cache',
        *tasks,
    ]
    env = dict(os.environ)
    # Signing properties live only in an isolated temporary Gradle user home.
    # Passwords are absent from command arguments and the ordinary user-home file.
    if signed:
        credentials()  # Check private file permissions before starting Gradle.
        command[1:1] = ['--gradle-user-home', str(SIGNING / 'gradle')]
    subprocess.run(command, env=env, check=True)


def test():
    gradle([
        ':ui:testFullDebugUnitTest',
        '--tests', 'app.aaps.ui.food.*',
        '--tests', 'app.aaps.ui.dialogs.FoodEntryCalculationTest',
        '--tests', 'app.aaps.ui.dialogs.WizardInputPrefillTest',
        ':implementation:testFullDebugUnitTest',
        '--tests', 'app.aaps.implementation.wizard.BolusWizardTest',
        '--tests', 'app.aaps.implementation.queue.CommandQueueImplementationTest',
        '--tests', 'app.aaps.implementation.utils.fabric.FabricPrivacyImplTest',
        ':plugins:sync:testFullDebugUnitTest',
        '--tests', 'app.aaps.plugins.sync.wear.receivers.WearDataReceiverTest',
        ':pump:medtronic:testFullDebugUnitTest', ':pump:rileylink:testFullDebugUnitTest',
    ])


def build():
    selected = variant()
    gradle([f':app:assemble{selected[0].upper()}{selected[1:]}'], signed=True)


def reports():
    ARTIFACT.mkdir(parents=True, exist_ok=True)
    combined = ET.Element('testsuites')
    modules = {}
    for module in TEST_MODULES:
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        files = sorted((REPO / module / 'build/test-results/testFullDebugUnitTest').glob('TEST-*.xml'))
        for path in files:
            suite = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(suite.get(key, '0'))
            # Gradle XML can contain environment/system properties and arbitrary logs.
            for parent in suite.iter():
                for child in list(parent):
                    if child.tag in ('properties', 'system-out', 'system-err'):
                        parent.remove(child)
            suite.set('module', module)
            combined.append(suite)
        modules[module] = {**totals, 'reportFiles': len(files)}
    for key in ('tests', 'failures', 'errors', 'skipped'):
        combined.set(key, str(sum(module[key] for module in modules.values())))
    ET.indent(combined)
    ET.ElementTree(combined).write(ARTIFACT / 'junit.xml', encoding='utf-8', xml_declaration=True)
    (ARTIFACT / 'tests.json').write_text(json.dumps(modules, indent=2) + '\n')
    print(f'Collected {combined.get("tests")} tests; incomplete/failed builds remain failed in Actions.')


def native_alignment(path, data):
    if data[:4] != b'\x7fELF' or data[4] != 2 or data[5] not in (1, 2):
        raise SystemExit(f'Invalid 64-bit ELF: {path}')
    endian = '<' if data[5] == 1 else '>'
    offset = struct.unpack_from(endian + 'Q', data, 32)[0]
    size, count = struct.unpack_from(endian + 'HH', data, 54)
    alignments = []
    for i in range(count):
        header = offset + i * size
        if struct.unpack_from(endian + 'I', data, header)[0] != 1:
            continue
        file_offset, address = struct.unpack_from(endian + 'QQ', data, header + 8)
        alignment = struct.unpack_from(endian + 'Q', data, header + 48)[0]
        if alignment < 16384 or (file_offset - address) % 16384:
            raise SystemExit(f'64-bit native library is not 16 KiB aligned: {path}')
        alignments.append(alignment)
    if not alignments:
        raise SystemExit(f'ELF has no load segments: {path}')
    return {'path': path, 'sha256': digest(data), 'loadAlignments': alignments}


def verify_healfi_identity(sdk, apk, badging):
    # Inspect the compiled APK, including permissions/providers contributed by
    # libraries; checking applicationId in Gradle alone misses install conflicts.
    if "application-label:'Healfi'" not in badging:
        raise SystemExit('Healfi launcher label is missing.')
    tree = subprocess.check_output([sdk / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml'], text=True)
    nodes, stack = [], []
    for line in tree.splitlines():
        element = re.match(r'(\s*)E: (\S+)', line)
        if element:
            depth = len(element[1])
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = {'element': element[2], 'attributes': {}}
            nodes.append(node)
            stack.append((depth, node))
        elif stack:
            attribute = re.search(r'A: (android:\w+)(?:\([^)]*\))?=\s*"([^"]*)"', line)
            if attribute:
                stack[-1][1]['attributes'][attribute[1]] = attribute[2]
    permissions = [node['attributes'].get('android:name', '') for node in nodes if node['element'] == 'permission']
    requested = [node['attributes'].get('android:name', '') for node in nodes if node['element'] == 'uses-permission']
    authorities = [node['attributes'].get('android:authorities', '') for node in nodes if node['element'] == 'provider']
    schemes = [node['attributes'].get('android:scheme', '') for node in nodes if node['element'] == 'data']
    expected = 'app.healfi.androidaps.weardata.permission'
    if expected not in permissions or expected not in requested or 'app.aaps.weardata.permission' in permissions + requested:
        raise SystemExit('Healfi Wear permission is not isolated from AAPS.')
    if 'app.healfi.androidaps.fileprovider' not in authorities or any(
        not (authority.startswith('app.healfi.androidaps.') or authority.endswith('.app.healfi.androidaps'))
        for authority in authorities
    ):
        raise SystemExit('Healfi provider authorities are not isolated.')
    if any(scheme in ('aaps', 'androidaps') for scheme in schemes):
        raise SystemExit('Healfi contains an OAuth callback belonging to AAPS.')
    return {'label': 'Healfi', 'customPermissions': permissions, 'providerAuthorities': authorities,
            'oauthSchemes': schemes, 'installsSeparatelyFromAaps': True,
            'physicalSideBySideInstallationTested': False}


def verify():
    selected = variant()
    flavor, build_type = re.fullmatch(r'(.*)(Release|Debug)', selected).groups()
    build_type = build_type.lower()
    apks = list((REPO / f'app/build/outputs/apk/{flavor}/{build_type}').glob('*.apk'))
    if len(apks) != 1:
        raise SystemExit('Expected exactly one assembled app APK.')
    apk = apks[0]
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
    signature = subprocess.check_output([sdk / 'apksigner', 'verify', '--verbose', '--print-certs', apk], text=True)
    if 'Verified using v2 scheme (APK Signature Scheme v2): true' not in signature:
        raise SystemExit('APK v2 signature verification failed.')
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signature, re.MULTILINE)
    values = credentials()
    certificate = subprocess.check_output([
        'keytool', '-exportcert', '-keystore', SIGNING / 'keystore.jks',
        '-alias', values['key_alias'], '-storepass:env', 'AAPS_VERIFY_STORE_PASSWORD',
    ], env=dict(os.environ, AAPS_VERIFY_STORE_PASSWORD=values['store_password']), stderr=subprocess.PIPE)
    if [digest(certificate)] != [value.lower() for value in certificates]:
        raise SystemExit('APK signer does not match the existing repository signing configuration.')
    subprocess.run([sdk / 'zipalign', '-c', '-P', '16', '4', apk], check=True)
    badging = subprocess.check_output([sdk / 'aapt', 'dump', 'badging', apk], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
    if package is None or package[1] != EXPECTED_PACKAGES[flavor]:
        raise SystemExit('Unexpected APK package identity.')
    separate_identity = verify_healfi_identity(sdk, apk, badging) if flavor == 'healfi' else None
    assets, native = {}, []
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise SystemExit('APK ZIP CRC check failed.')
        for path in sorted((REPO / 'ui/src/main/assets/food').rglob('*')):
            if path.is_file():
                name = 'assets/' + path.relative_to(REPO / 'ui/src/main/assets').as_posix()
                data = archive.read(name)
                if digest(data) != sha_file(path):
                    raise SystemExit(f'Bundled asset does not match source: {name}')
                assets[name] = {'bytes': len(data), 'sha256': digest(data)}
        for name in archive.namelist():
            if name.startswith(('lib/arm64-v8a/', 'lib/x86_64/')) and name.endswith('.so'):
                native.append(native_alignment(name, archive.read(name)))
    if not assets or not native or not any(item['path'].startswith('lib/arm64-v8a/') for item in native):
        raise SystemExit('Expected food assets and 64-bit native libraries were not verified.')
    tests = json.loads((ARTIFACT / 'tests.json').read_text())
    for module, counts in tests.items():
        if counts['tests'] <= 0 or any(counts[key] for key in ('failures', 'errors', 'skipped')):
            raise SystemExit(f'Missing or unsuccessful test results: {module}')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if commit != os.environ['GITHUB_SHA']:
        raise SystemExit('Checked-out source differs from the Actions source commit.')
    if subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], text=True).strip():
        raise SystemExit('Tracked source files changed during validation.')
    destination = ARTIFACT / f'aaps-validation-{selected}-{commit[:12]}.apk'
    report = {
        'sourceCommit': commit,
        'runUrl': f"https://github.com/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
        'variant': selected,
        'apk': {'file': destination.name, 'bytes': apk.stat().st_size, 'sha256': sha_file(apk),
                'package': package[1], 'versionCode': package[2], 'versionName': package[3],
                'signerCertificateSha256': certificates[0].lower(), 'matchesConfiguredKeystore': True,
                'v2Signature': 'passed', 'zipCrc': 'passed', 'zipAlignment16KiB': 'passed'},
        'tests': tests,
        'totalTests': sum(counts['tests'] for counts in tests.values()),
        'junitSha256': sha_file(ARTIFACT / 'junit.xml'),
        'bundledFoodAssets': assets,
        'nativeElf16KiB': native,
        'toolchain': {'jdk': 21, 'compileSdk': 36, 'buildTools': '35.0.0',
                      'gradleHeapGiB': 3, 'workers': 2, 'kotlinCompiler': 'in-process'},
        'separateApplicationIdentity': separate_identity,
        'scope': {'physicalPumpConnected': False, 'nativeRenderingTested': False,
                  'installedPhoneCertificateCompared': False, 'clinicalValidation': False,
                  'note': 'Signed software candidate; JVM and APK checks do not validate real radio, phone operation or clinical dosing.'},
    }
    # Only a completely verified APK enters the upload allowlist.
    shutil.copyfile(apk, destination)
    (ARTIFACT / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'Verified candidate {destination.name}; signer SHA-256 {certificates[0].lower()}')


def cleanup():
    # AGP serializes store/key passwords into this generated intermediate even
    # when build/configuration caches are disabled. Remove it from every module,
    # while leaving unrelated JSON files and paths outside this checkout alone.
    removed = 0
    for path in REPO.glob('**/build/intermediates/signing_config_data/**/signing-config-data.json'):
        relative = path.relative_to(REPO)
        ancestors = [REPO.joinpath(*relative.parts[:i]) for i in range(1, len(relative.parts) + 1)]
        if any(item.is_symlink() for item in ancestors):
            continue
        path.unlink()
        removed += 1
    if SIGNING.is_symlink():
        SIGNING.unlink()
    else:
        try:
            shutil.rmtree(SIGNING)
        except FileNotFoundError:
            pass
    print(f'Private temporary signing files and {removed} generated signing intermediates removed.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('prepare', 'test', 'build', 'reports', 'verify', 'cleanup'))
    operation = parser.parse_args().operation
    try:
        globals()[operation]()
    except subprocess.CalledProcessError:
        raise SystemExit(f'{operation} failed; see the failed tool step above.')


if __name__ == '__main__':
    main()
