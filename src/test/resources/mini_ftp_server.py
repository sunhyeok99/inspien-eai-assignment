"""테스트 전용 최소 FTP 서버 (Python 표준 라이브러리만 사용).

사용: python3 mini_ftp_server.py <port> <root_dir> [--fail-stor]
지원 명령: USER PASS OPTS TYPE CWD PASV STOR SIZE DELE QUIT (계정: test / test)
"""
import os
import socket
import sys
import threading


def handle(conn, root, fail_stor):
    f = conn.makefile("rwb", buffering=0)

    def send(line):
        f.write((line + "\r\n").encode("utf-8"))

    send("220 mini ftp ready")
    cwd = root
    data_sock = None
    user = None
    while True:
        raw = f.readline()
        if not raw:
            break
        line = raw.decode("utf-8").rstrip("\r\n")
        cmd, _, arg = line.partition(" ")
        cmd = cmd.upper()
        if cmd == "USER":
            user = arg
            send("331 password required")
        elif cmd == "PASS":
            send("230 logged in" if (user == "test" and arg == "test") else "530 login incorrect")
        elif cmd == "OPTS":
            send("200 UTF8 set to on")
        elif cmd == "TYPE":
            send("200 type set")
        elif cmd == "CWD":
            path = os.path.join(root, arg.strip("/"))
            if os.path.isdir(path):
                cwd = path
                send("250 ok")
            else:
                send("550 no such directory")
        elif cmd == "PASV":
            data_sock = socket.socket()
            data_sock.bind(("127.0.0.1", 0))
            data_sock.listen(1)
            port = data_sock.getsockname()[1]
            # 일부러 내부 IP 를 알려줘 제어 연결 호스트 사용 로직을 검증
            send("227 Entering Passive Mode (10,0,0,1,%d,%d)" % (port // 256, port % 256))
        elif cmd == "STOR":
            if fail_stor:
                send("451 local error in processing")
                continue
            send("150 opening data connection")
            dc, _ = data_sock.accept()
            chunks = []
            while True:
                b = dc.recv(65536)
                if not b:
                    break
                chunks.append(b)
            dc.close()
            data_sock.close()
            with open(os.path.join(cwd, arg), "wb") as out:
                out.write(b"".join(chunks))
            send("226 transfer complete")
        elif cmd == "SIZE":
            p = os.path.join(cwd, arg)
            send("213 %d" % os.path.getsize(p) if os.path.exists(p) else "550 not found")
        elif cmd == "DELE":
            p = os.path.join(cwd, arg)
            if os.path.exists(p):
                os.remove(p)
                send("250 deleted")
            else:
                send("550 not found")
        elif cmd == "QUIT":
            send("221 bye")
            break
        else:
            send("502 not implemented")
    conn.close()


def main():
    port = int(sys.argv[1])
    root = sys.argv[2]
    fail_stor = "--fail-stor" in sys.argv
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(5)
    print("ready", flush=True)
    while True:
        c, _ = srv.accept()
        threading.Thread(target=handle, args=(c, root, fail_stor), daemon=True).start()


if __name__ == "__main__":
    main()
