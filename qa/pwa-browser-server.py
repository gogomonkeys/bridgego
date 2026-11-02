"""Local-only real production dist PWA inspection. NOT shipped application routes.
Serves /qa-pwa.html with explicit browser storage inspection UI and /qa-next toggles
an otherwise identical worker cache version so waiting lifecycle can be observed.
Never run publicly. Run from repository root, then stop with Ctrl-C after QA.
"""
from http.server import ThreadingHTTPServer,SimpleHTTPRequestHandler
from pathlib import Path
import json,sys
FIXTURE="--fixture" in sys.argv
ROOT=Path(__file__).resolve().parents[1];NEXT=False
class Handler(SimpleHTTPRequestHandler):
    def __init__(self,*a,**kw):super().__init__(*a,directory=str(ROOT/('qa/ui-preview/dist' if FIXTURE else 'frontend/dist')),**kw)
    def do_GET(self):
        if FIXTURE and self.path.startswith('/launcher.html') and 'insets=1' in self.path:
            page=(ROOT/'qa/ui-preview/dist/launcher.html').read_text()
            page+='<script>document.querySelector("iframe").addEventListener("load",function(){const s=this.contentDocument.documentElement.style;s.setProperty("--bridge-safe-top","47px");s.setProperty("--bridge-safe-bottom","34px");});</script>'
            return self.send(page.encode(),'text/html')
        if self.path=='/qa-pwa.html':return self.send((ROOT/'qa/pwa-browser-diagnostics.html').read_bytes(),'text/html')
        if self.path=='/qa-next':
            global NEXT;NEXT=True;return self.send(b'{"testWorkerVariant":true}','application/json')
        if self.path=='/sw.js':
            code=('/* isolated QA worker lifecycle only */ const REV = '+str(int(NEXT))+';') if FIXTURE else (ROOT/'frontend/dist/sw.js').read_text()
            if NEXT:code=code.replace('bridgego-static-','bridgego-static-qa-next-',1)
            return self.send(code.encode(),'text/javascript')
        if self.path.startswith('/api/') or self.path.startswith('/health/'):
            return self.send(b'{"code":"QA_NO_BACKEND","message":"Static QA server only"}','application/json',503)
        return super().do_GET()
    def send(self,data,mime,status=200):
        self.send_response(status);self.send_header('Content-Type',mime);self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
ThreadingHTTPServer(('127.0.0.1',8768 if FIXTURE else 8767),Handler).serve_forever()
