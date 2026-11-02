"""Raw loopback websocket that deliberately never answers control PING."""
import socket,os,base64,json,struct
def raw_socket(token,room):
 s=socket.create_connection(('127.0.0.1',8080),timeout=5)
 key=base64.b64encode(os.urandom(16)).decode()
 s.sendall(f'GET /ws HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n'.encode())
 response=b''
 while b'\r\n\r\n' not in response:response+=s.recv(4096)
 assert b'101' in response.split(b'\r\n')[0]
 payload=json.dumps({'type':'subscribe','token':token,'roomId':room}).encode();mask=os.urandom(4)
 prefix=bytes([0x81,0x80|len(payload)]) if len(payload)<126 else bytes([0x81,0x80|126])+struct.pack('!H',len(payload))
 s.sendall(prefix+mask+bytes(c^mask[i%4] for i,c in enumerate(payload)))
 return s
