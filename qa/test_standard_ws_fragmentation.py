"""Prove QA websockets client reassembles split UTF-8 text with interleaved Ping."""
import base64,hashlib,json,socket,struct,sys,threading,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'build-tools/python-qa'))
from websockets.sync.client import connect
from websockets.frames import Frame,OP_TEXT,OP_CONT,OP_PING,OP_CLOSE
class StandardFragmentationTest(unittest.TestCase):
 def test_large_utf8_interleaved_control_and_bounded_client(self):
  raw=json.dumps({'type':'snapshot','payload':'牌'*10000},ensure_ascii=False).encode();listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();port=listener.getsockname()[1];observed=[];errors=[]
  def server():
   try:
    conn,_=listener.accept();conn.settimeout(5);buf=b''
    while b'\r\n\r\n' not in buf:buf+=conn.recv(4096)
    key=next(l.split(b':',1)[1].strip() for l in buf.split(b'\r\n') if l.lower().startswith(b'sec-websocket-key:'))
    accept=base64.b64encode(hashlib.sha1(key+b'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest())
    conn.sendall(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+b'\r\n\r\n')
    conn.sendall(Frame(OP_TEXT,raw[:8192],fin=False).serialize(mask=False)+Frame(OP_PING,b'probe').serialize(mask=False)+Frame(OP_CONT,raw[8192:],fin=True).serialize(mask=False))
    f=conn.makefile('rb')
    for _ in range(2):
     h=f.read(2);op=h[0]&15;n=h[1]&127
     if n==126:n=struct.unpack('!H',f.read(2))[0]
     mask=f.read(4);data=f.read(n);data=bytes(x^mask[i%4] for i,x in enumerate(data));observed.append((op,data))
     if op==8:conn.sendall(Frame(OP_CLOSE,data).serialize(mask=False));break
    conn.close()
   except Exception as e:errors.append(repr(e))
   finally:listener.close()
  worker=threading.Thread(target=server);worker.start()
  with connect(f'ws://127.0.0.1:{port}/',ping_interval=None,max_size=262144,max_queue=16) as ws:
   self.assertEqual(json.loads(ws.recv()),json.loads(raw))
  worker.join(5);self.assertFalse(worker.is_alive());self.assertEqual(errors,[]);self.assertIn((10,b'probe'),observed)
if __name__=='__main__':unittest.main()
