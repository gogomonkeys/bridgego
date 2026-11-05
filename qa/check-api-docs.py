#!/usr/bin/env python3
"""Offline API/document drift gate. --render writes the human-readable catalog.
Optional --spec uses openapi-spec-validator installed in a tooling-only environment.
Does not call production, inspect credentials or change application state.
"""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'backend/src/main/java/com/bridge/room'
DOCS = ROOT / 'docs/api'


def routes():
    found = set()
    for name, prefix in [('RoomController.java', '/api'), ('ServiceHealthController.java', ''), ('GoogleAuthController.java', '/api/auth')]:
        for method, path in re.findall(r'@(Get|Post|Put|Delete|Patch)Mapping\("([^"]+)"\)', (JAVA / name).read_text()):
            found.add((method.lower(), prefix + path))
    return found


def failure_codes():
    found = set()
    for p in JAVA.glob('*.java'):
        text = p.read_text()
        found.update(re.findall(r'(?:\bfail\(|\bfailure\(response,)\s*\d{3}\s*,\s*"([A-Z_]+)"', text))
    # Controller's Map response errors and socket reject errors do not call fail().
    found.update(re.findall(r'"code",\s*"([A-Z_]+)"', (JAVA / 'RoomController.java').read_text()))
    found.update(re.findall(r'\breject\(c,\s*"([A-Z_]+)"', (JAVA / 'RoomSocket.java').read_text()))
    return found


def render(catalog):
    lines = ['# 錯誤碼與處置索引', '', '> 由 `errors.json` 產生，請編輯 JSON 後執行 `python qa/check-api-docs.py --render`。',
             '', 'HTTP 狀態並不決定是否能重試。所有改寫請求都不得盲目自動重送；已接受命令只能以原 actionId 及完全相同 payload 查回結果。', '',
             '| code | HTTP | 意義 | 客戶端／排查處置 |', '|---|---|---|---|']
    for e in catalog['applicationErrors']:
        lines.append('| ' + ' | '.join([e['code'], ', '.join(map(str, e['httpStatuses'])) or 'WS JSON', e['meaning'], e['handling']]) + ' |')
    lines += ['', '## WebSocket close code', '', '| code | 意義 | 處置 |', '|---|---|---|']
    for e in catalog['websocketCloseCodes']:
        lines.append(f"| {e['code']} | {e['meaning']} | {e['handling']} |")
    lines += ['', '## 客戶端分類（不是 HTTP wire code）', '']
    for e in catalog['clientOnlyErrors']:
        lines.append(f"- **{e['code']}**：{e['meaning']}。{e['handling']}。")
    lines += ['', '原始碼位置、HTTP 狀態集合及訊息範例見 [errors.json](errors.json)。此表不收錄私牌、token 或原始請求內容。', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--render', action='store_true')
    parser.add_argument('--spec', action='store_true', help='Also validate full OpenAPI specification; requires tooling package')
    args = parser.parse_args()
    spec = json.loads((DOCS / 'openapi.json').read_text())
    catalog = json.loads((DOCS / 'errors.json').read_text())
    assert spec['openapi'] == '3.0.3'
    actual = {(method, path) for path, methods in spec['paths'].items() for method in methods if method in {'get', 'post', 'put', 'patch', 'delete'}}
    assert actual == routes(), f'Endpoint drift: missing={routes()-actual}, extra={actual-routes()}'
    ids = [op['operationId'] for methods in spec['paths'].values() for op in methods.values()]
    assert len(ids) == len(set(ids)), 'Duplicate operationId'
    def walk(value):
        if isinstance(value, dict):
            if '$ref' in value:
                ref = value['$ref']; assert ref.startswith('#/'), f'External reference not offline: {ref}'
                target = spec
                for part in ref[2:].split('/'): target = target[part.replace('~1', '/').replace('~0', '~')]
            for child in value.values(): walk(child)
        elif isinstance(value, list):
            for child in value: walk(child)
    walk(spec)
    for path, methods in spec['paths'].items():
        for op in methods.values():
            names = {p['name'] for p in op.get('parameters', []) if p['in'] == 'path' and p['required']}
            assert names == set(re.findall(r'\{(\w+)\}', path)), f'Path parameter drift {path}'
            if path.startswith('/api/') and path not in {'/api/sessions', '/api/auth/config'}: assert op['security'] == [{'guestBearer': []}]
            else: assert op['security'] == []
            assert '200' in op['responses']
    listed = {e['code'] for e in catalog['applicationErrors']}
    assert len(listed) == len(catalog['applicationErrors']), 'Duplicate catalog code'
    assert listed == failure_codes(), f'Error drift: missing={failure_codes()-listed}, stale={listed-failure_codes()}'
    messages = (ROOT / 'frontend/src/error-messages.ts').read_text()
    keys = set(re.findall(r'^\s*([A-Z_]+):', messages, re.M))
    assert listed <= keys, f'Missing UI messages: {listed-keys}'
    for e in catalog['applicationErrors']:
        assert e['automaticMutationRetry'] is False
        for source in e['sources']: assert (ROOT / source).is_file(), source
    expected = render(catalog)
    target = DOCS / 'errors.zh-TW.md'
    if args.render: target.write_text(expected)
    else: assert target.read_text() == expected, 'Rendered catalog drift; run --render'
    if args.spec:
        from openapi_spec_validator import validate
        validate(spec)
    print(f'PASS: {len(actual)} HTTP operations; {len(listed)} application errors; references, auth and UI translation coverage; rendered catalog' + ('; full OpenAPI schema' if args.spec else ' (structural drift gate only; use --spec for full OpenAPI validation)'))


if __name__ == '__main__':
    main()
