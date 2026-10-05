#!/usr/bin/env python3
"""Verify a completed local Android gallery and unchanged raw capture bytes.

This is a browser/gallery check. It does not run the Android APK, verify the
pump, or establish that every scenario transition passed on Android.
"""
from __future__ import annotations
import argparse
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit

PNG_SIGNATURE = b'\x89PNG\r\n\x1a\n'
HEX64 = re.compile(r'^[0-9a-f]{64}$', re.I)

class References(HTMLParser):
    def __init__(self):
        super().__init__()
        self.images = []
        self.png_links = []
    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag == 'img':
            self.images.append(attrs.get('src', ''))
        if tag == 'a':
            href = attrs.get('href', '')
            if unquote(urlsplit(href).path).lower().endswith('.png'):
                self.png_links.append(href)

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def capture_records(path, category):
    document = json.loads(path.read_text(encoding='utf-8'))
    entries = document.get('captures', document.get('screenshots', []))
    if not isinstance(entries, list) or not entries:
        raise ValueError(f'{category}: evidence has no capture list')
    result = []
    for entry in entries:
        expected = str(entry.get('sha256') or entry.get('imageSha256') or '').lower()
        if not HEX64.fullmatch(expected):
            raise ValueError(f'{category}: capture lacks SHA-256: {entry.get("name")}')
        source = entry.get('sourceCommit', document.get('sourceCommit'))
        if document.get('sourceCommit') and source != document['sourceCommit']:
            raise ValueError(f'{category}: capture source differs from report')
        result.append({'category': category, 'name': entry.get('name'), 'sha256': expected,
                       'sourceCommit': source, 'apkSha256': entry.get('apkSha256', document.get('apkSha256'))})
    return document, result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gallery', type=Path, required=True)
    parser.add_argument('--scenario-dir', type=Path, required=True)
    parser.add_argument('--before-json', type=Path, required=True)
    parser.add_argument('--after-json', type=Path, required=True)
    parser.add_argument('--expected-after-source', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--screenshots-dir', type=Path,
                        help='Optional browser gallery previews, never substitute these for native captures')
    args = parser.parse_args()
    gallery = args.gallery.resolve()
    scenario = args.scenario_dir.resolve()
    report = {'result': 'NOT_RUN', 'gallery': str(gallery), 'viewports': [],
              'rawCaptures': [], 'errors': [], 'nativePixelsChanged': None,
              'scope': 'Gallery browser behavior and raw PNG hashes, not Android scenario/device/pump validation.'}
    missing = [str(p) for p in [gallery, args.before_json, args.after_json] if not p.is_file()]
    if missing:
        report['reason'] = 'Completed gallery/evidence not available'
        report['missingFiles'] = missing
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'result': 'NOT_RUN', 'reason': report['reason'], 'missingFiles': missing}, ensure_ascii=False))
        return 2
    try:
        if not gallery.is_relative_to(scenario):
            raise ValueError('Gallery lies outside the scenario directory')
        before, old_records = capture_records(args.before_json, 'before')
        after, new_records = capture_records(args.after_json, 'after')
        expected_source = args.expected_after_source.lower()
        if not re.fullmatch(r'[0-9a-f]{40}', expected_source):
            raise ValueError('Expected after source must be an exact 40-character commit')
        if str(after.get('sourceCommit', '')).lower() != expected_source:
            raise ValueError('After JSON belongs to another source commit')
        report['expectedAfterSource'] = expected_source
        records = old_records + new_records
        document = gallery.read_text(encoding='utf-8')
        if 'ПОСЛЕ ЕЩЁ НЕ ГОТОВО' in document or 'После: ожидается Android-съёмка' in document:
            raise ValueError('Pending draft gallery must not pass a completed-gallery check')
        references = References()
        references.feed(document)
        if len(references.images) < 2:
            raise ValueError('Completed comparison needs at least two actual images')
        def local_png(reference):
            url = urlsplit(reference)
            if url.scheme or url.netloc or not url.path:
                raise ValueError('PNG must use a local relative URL: ' + reference)
            path = (gallery.parent / unquote(url.path)).resolve()
            if not path.is_relative_to(scenario):
                raise ValueError('PNG URL escapes scenario directory: ' + reference)
            if not path.is_file() or path.suffix.lower() != '.png':
                raise ValueError('PNG URL does not resolve to an existing PNG: ' + reference)
            if not path.read_bytes().startswith(PNG_SIGNATURE):
                raise ValueError('Referenced file is not a PNG: ' + reference)
            return path
        image_paths = {local_png(src) for src in references.images}
        raw_link_paths = {local_png(href) for href in references.png_links}
        if image_paths != raw_link_paths:
            raise ValueError('Displayed captures and full raw PNG links must refer to the same files')
        initial_hashes = {}
        represented_categories = set()
        for path in sorted(image_paths):
            actual = digest(path)
            matches = [record for record in records if record['sha256'] == actual]
            if not matches:
                raise ValueError('PNG bytes do not match either evidence JSON: ' + str(path))
            represented_categories.update(record['category'] for record in matches)
            initial_hashes[path] = actual
            report['rawCaptures'].append({'file': str(path.relative_to(scenario)), 'sha256': actual,
                                          'matchingEvidence': matches})
        if represented_categories != {'before', 'after'}:
            raise ValueError('Gallery does not contain both before and final-source after evidence')

        from playwright.sync_api import sync_playwright
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True,
                                                  args=['--no-sandbox', '--disable-dev-shm-usage'])
            try:
                for name, width, height in [('desktop', 1440, 1000), ('mobile', 360, 800)]:
                    context = browser.new_context(viewport={'width': width, 'height': height}, device_scale_factor=1)
                    page = context.new_page()
                    console_errors = []
                    page_errors = []
                    page.on('console', lambda message: console_errors.append(message.text) if message.type == 'error' else None)
                    page.on('pageerror', lambda error: page_errors.append(str(error)))
                    page.goto(gallery.as_uri(), wait_until='load', timeout=30000)
                    # All lazy captures must actually decode, including those below the fold.
                    page.locator('img').evaluate_all("images => images.forEach(image => image.loading = 'eager')")
                    page.wait_for_function("Array.from(document.images).every(image => image.complete && image.naturalWidth > 0 && image.naturalHeight > 0)", timeout=15000)
                    images = page.locator('img').evaluate_all("images => images.map(image => ({src:image.getAttribute('src'),complete:image.complete,naturalWidth:image.naturalWidth,naturalHeight:image.naturalHeight,declaredWidth:parseInt(image.getAttribute('width') || '0'),declaredHeight:parseInt(image.getAttribute('height') || '0')}))")
                    states = []
                    for opened in [False, True]:
                        page.locator('details').evaluate_all('nodes => nodes.forEach(node => node.open = ' + ('true' if opened else 'false') + ')')
                        page.wait_for_timeout(100)
                        geometry = page.evaluate("({clientWidth:document.documentElement.clientWidth,scrollWidth:document.documentElement.scrollWidth,bodyScrollWidth:document.body.scrollWidth})")
                        overflowing = page.locator('body *').evaluate_all("nodes => nodes.map(node => ({tag:node.tagName,className:node.className,left:node.getBoundingClientRect().left,right:node.getBoundingClientRect().right})).filter(node => node.right > innerWidth + 1 || node.left < -1).slice(0,20)")
                        states.append({'provenanceDetailsOpen': opened, **geometry, 'overflowingElements': overflowing})
                        if max(geometry['scrollWidth'], geometry['bodyScrollWidth']) > geometry['clientWidth'] + 1:
                            report['errors'].append({'viewport': name, 'detailsOpen': opened, 'horizontalPageOverflow': geometry})
                    if console_errors or page_errors:
                        report['errors'].append({'viewport': name, 'consoleErrors': console_errors, 'pageErrors': page_errors})
                    for image in images:
                        if not image['complete'] or not image['naturalWidth'] or not image['naturalHeight']:
                            report['errors'].append({'viewport': name, 'imageNotLoaded': image})
                        if image['declaredWidth'] and image['declaredWidth'] != image['naturalWidth'] or image['declaredHeight'] and image['declaredHeight'] != image['naturalHeight']:
                            report['errors'].append({'viewport': name, 'incorrectDeclaredImageDimensions': image})
                    viewport_report = {'name': name, 'width': width, 'height': height, 'images': images,
                                       'consoleErrors': console_errors, 'pageErrors': page_errors,
                                       'pageGeometryStates': states}
                    if args.screenshots_dir:
                        args.screenshots_dir.mkdir(parents=True, exist_ok=True)
                        preview = args.screenshots_dir / f'gallery-{name}.png'
                        page.locator('details').evaluate_all('nodes => nodes.forEach(node => node.open = false)')
                        page.screenshot(path=str(preview), full_page=True)
                        viewport_report['browserPreview'] = str(preview)
                    report['viewports'].append(viewport_report)
                    context.close()
            finally:
                browser.close()
        unchanged = all(path.is_file() and digest(path) == sha for path, sha in initial_hashes.items())
        report['nativePixelsChanged'] = not unchanged
        if not unchanged:
            report['errors'].append({'rawPngChangedDuringBrowserCheck': True})
        report['result'] = 'PASS' if not report['errors'] else 'FAIL'
    except Exception as error:
        report['result'] = 'FAIL'
        report['errors'].append({'type': type(error).__name__, 'message': str(error)})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'result': report['result'], 'viewportsChecked': len(report['viewports']),
                      'rawPngCount': len(report['rawCaptures']), 'nativePixelsChanged': report['nativePixelsChanged'],
                      'errors': report['errors']}, ensure_ascii=False))
    return 0 if report['result'] == 'PASS' else 1

if __name__ == '__main__':
    sys.exit(main())
