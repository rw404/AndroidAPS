#!/usr/bin/env python3
"""Check the native Material alert layout/style contract against its actual AAR.

This checks style inheritance and layout dimensions; it does not replace an
Android inflation/runtime test or inspect the clinical confirmation payload.
"""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ANDROID = '{http://schemas.android.com/apk/res/android}'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--material-aar', type=Path, required=True)
parser.add_argument('--styles', type=Path, required=True)
parser.add_argument('--output', type=Path)
parser.add_argument('--dimens', type=Path, default=Path('core/ui/src/main/res/values/healfi_scenario_dimens.xml'))
args = parser.parse_args()
with ZipFile(args.material_aar) as aar:
    vendor_xml = aar.read('res/values/values.xml')
    vendor_styles = {e.attrib['name']: e for e in ET.fromstring(vendor_xml).findall('style')}
    own_styles = {e.attrib['name']: e for e in ET.parse(args.styles).getroot().findall('style')}
    styles = vendor_styles | own_styles

    def parent(name):
        element = styles.get(name)
        if element is None:
            return None
        explicit = element.attrib.get('parent')
        if explicit is not None:
            return explicit.removeprefix('@style/') or None
        return name.rsplit('.', 1)[0] if '.' in name else None

    def lineage(name):
        result = []
        while name:
            if name in result:
                raise ValueError('Cyclic style inheritance: ' + name)
            result.append(name)
            name = parent(name)
        return result

    def bag(name):
        result = {}
        for candidate in reversed(lineage(name)):
            element = styles.get(candidate)
            if element is not None:
                result.update({i.attrib['name']: (i.text or '').strip() for i in element.findall('item')})
        return result

    theme = bag('HealfiScenario.DialogTheme')
    errors = []
    nodes = []
    visited_layouts = []

    def check_layout(resource):
        visited_layouts.append(resource)
        for node in ET.fromstring(aar.read(resource)).iter():
            if node.tag == 'include':
                include = node.attrib.get('layout', '')
                if include.startswith('@layout/'):
                    check_layout('res/layout/' + include[len('@layout/'):] + '.xml')
                continue
            style_ref = node.attrib.get('style', '')
            if style_ref.startswith('?attr/'):
                style_ref = theme.get(style_ref[len('?attr/'):], '')
            inherited = bag(style_ref[len('@style/'):]) if style_ref.startswith('@style/') else {}
            width = node.attrib.get(ANDROID + 'layout_width', inherited.get('android:layout_width'))
            height = node.attrib.get(ANDROID + 'layout_height', inherited.get('android:layout_height'))
            item = {'layout': resource, 'view': node.tag, 'id': node.attrib.get(ANDROID + 'id'),
                    'sourceStyle': node.attrib.get('style'), 'resolvedStyle': style_ref or None,
                    'layoutWidth': width, 'layoutHeight': height}
            nodes.append(item)
            if width is None or height is None:
                errors.append({'view': item['id'] or node.tag, 'layout': resource,
                               'missing': [x for x, value in [('layout_width', width), ('layout_height', height)] if value is None]})

    check_layout('res/layout/mtrl_alert_dialog.xml')
    widgets = []
    dimen_values = {e.attrib['name']: (e.text or '').strip() for e in ET.parse(args.dimens).getroot().findall('dimen')}
    def dimension(value):
        return dimen_values.get(value[len('@dimen/'):], value) if value and value.startswith('@dimen/') else value
    for attr, vendor in [('materialAlertDialogBodyTextStyle', 'MaterialAlertDialog.MaterialComponents.Body.Text'),
                         ('materialAlertDialogTitleTextStyle', 'MaterialAlertDialog.MaterialComponents.Title.Text')]:
        ref = theme.get(attr, '')
        name = ref.removeprefix('@style/')
        chain = lineage(name) if ref.startswith('@style/') else []
        resolved = bag(name) if chain else {}
        appearance_ref = resolved.get('android:textAppearance', '')
        appearance = bag(appearance_ref.removeprefix('@style/')) if appearance_ref.startswith('@style/') else {}
        effective = appearance | resolved
        font_keys = ['android:textSize', 'android:fontFamily', 'android:letterSpacing', 'android:includeFontPadding']
        font = {key: dimension(effective.get(key)) for key in font_keys}
        typography_target = 'HealfiScenario.Body' if attr == 'materialAlertDialogBodyTextStyle' else 'HealfiScenario.Title'
        intended = {key: dimension(bag(typography_target).get(key)) for key in font_keys}
        widgets.append({'themeAttribute': attr, 'resolvedStyle': name, 'lineage': chain,
                        'textAppearance': appearance_ref or None, 'effectiveFontProperties': font, 'intendedFontProperties': intended})
        if font != intended:
            errors.append({'themeAttribute': attr, 'typographyMismatch': {'effective': font, 'intended': intended}})
        if vendor not in chain:
            errors.append({'themeAttribute': attr, 'missingWidgetParent': vendor})

    report = {'result': 'PASS' if not errors else 'FAIL',
              'materialAarSha256': hashlib.sha256(args.material_aar.read_bytes()).hexdigest(),
              'sharedStylesSha256': hashlib.sha256(args.styles.read_bytes()).hexdigest(),
              'namedOverlay': 'HealfiScenario.DialogTheme',
              'overlayLineage': lineage('HealfiScenario.DialogTheme'),
              'layoutsChecked': visited_layouts, 'layoutNodeCount': len(nodes),
              'nativeLayoutNodes': nodes, 'typographyWidgetStyles': widgets, 'errors': errors,
              'scope': 'Actual vendor XML plus named shared style inheritance; Android runtime still required.'}
    if args.output:
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'result': report['result'], 'layoutsChecked': visited_layouts,
                      'layoutNodeCount': len(nodes), 'errors': errors}, ensure_ascii=False))
    raise SystemExit(0 if not errors else 1)
