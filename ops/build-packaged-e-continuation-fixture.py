#!/usr/bin/env python3
"""Build a derived, LOCAL-ONLY executable fixture from the real packaged server.
No production class or dependency is replaced. The only differences are Start-Class
and additional explicitly named test harness classes. This is not the production JAR.
No network, credentials, deployment, or persistent settings are accessed.
"""
import argparse, hashlib, json, pathlib, zipfile
PREFIX = 'com/bridge/quartercore/PackagedE'
MAIN = 'com.bridge.quartercore.PackagedEContinuationMain'

def build(source, classes, output):
    source, classes, output = map(pathlib.Path, (source, classes, output))
    if source.resolve() == output.resolve():
        raise ValueError('Never overwrite the production artifact')
    additions = sorted(p for p in classes.glob(PREFIX + '*.class') if p.name.startswith(('PackagedEHandoffMain','PackagedEContinuationMain')))
    if not any(p.name == 'PackagedEContinuationMain.class' for p in additions):
        raise ValueError('Compile the test-only harness first')
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(output, 'x') as fixture:
        names = original.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate production ZIP entries')
        for item in original.infolist():
            data = original.read(item.filename)
            if item.filename == 'META-INF/MANIFEST.MF':
                lines = data.decode('utf-8').replace('\r\n','\n').splitlines()
                if sum(line.startswith('Start-Class: ') for line in lines) != 1:
                    raise ValueError('Expected Spring Boot executable manifest')
                lines = ['Start-Class: ' + MAIN if line.startswith('Start-Class: ') else line for line in lines]
                data = ('\r\n'.join(lines) + '\r\n').encode()
            fixture.writestr(item, data)
        for p in additions:
            name = 'BOOT-INF/classes/' + p.relative_to(classes).as_posix()
            if name in names:
                raise ValueError('Test harness must not replace production code')
            fixture.writestr(name, p.read_bytes())
    hashes = {}
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(output) as fixture:
        for name in original.namelist():
            if name != 'META-INF/MANIFEST.MF' and original.read(name) != fixture.read(name):
                raise AssertionError('Production artifact entry changed: ' + name)
            if name.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
                hashes[name] = hashlib.sha256(original.read(name)).hexdigest()
    output.with_suffix('.provenance.json').write_text(json.dumps({'productionEntriesUnchanged': True, 'sha256ByEntry': hashes}, sort_keys=True, indent=2))
    print('LOCAL_DERIVED_FIXTURE production_entries_identical=true')
    print('production_sha256=' + hashlib.sha256(source.read_bytes()).hexdigest())
    print('fixture_sha256=' + hashlib.sha256(output.read_bytes()).hexdigest())

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--production-jar',required=True)
    parser.add_argument('--test-classes',required=True)
    parser.add_argument('--output',required=True)
    a=parser.parse_args()
    build(a.production_jar,a.test_classes,a.output)
