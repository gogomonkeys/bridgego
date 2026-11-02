"""Fresh HTTP boundary checks against a loopback backend, not browser input tests."""
import json
from pathlib import Path
from integration import api
cases = [
    ('one CJK', '小', '小'),
    ('five CJK', '一二三四五', '一二三四五'),
    ('five Latin', 'abcde', 'abcde'),
    ('five emoji', '😀'*5, '😀'*5),
    ('five family graphemes', '👨‍👩‍👧‍👦'*5, '👨‍👩‍👧‍👦'*5),
    ('five flags', '🇹🇼'*5, '🇹🇼'*5),
    ('trim and NFC', '\ufeff\u00a0　e\u0301　', 'é'),
    ('five decomposed accents', 'e\u0301'*5, 'é'*5),
    ('six CJK', '一二三四五六', None),
    ('six Latin', 'abcdef', None),
    ('six emoji', '😀'*6, None),
    ('six family graphemes', '👨‍👩‍👧‍👦'*6, None),
    ('blank', '　 \u00a0', None),
    ('null', None, None),
    ('control', 'a\nb', None),
    ('pathological sequence', 'a'+'\u0301'*300, None),
]
results=[]
for label, value, expected in cases:
    status, body = api('POST', '/api/sessions', data={'displayName': value})
    passed = status in (200, 201) and body.get('displayName') == expected if expected is not None else status == 400 and body.get('code') == 'INVALID_NAME'
    results.append({'case': label, 'status': 'PASS' if passed else 'FAIL', 'httpStatus': status})
    assert passed, (label, status, body)
Path(__file__).with_name('nickname-boundary-results.json').write_text(json.dumps(results, indent=2)+'\n')
print(f'{len(results)} nickname HTTP boundary checks passed')
