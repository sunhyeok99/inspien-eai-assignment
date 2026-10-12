package com.assignment.eai.receiver;

import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 영수증 업로드에 필요한 명령만 구현한 최소 FTP 클라이언트 (RFC 959, Passive 모드).
 * USER/PASS, OPTS UTF8, TYPE I, CWD, PASV(EPSV), STOR, SIZE, DELE, QUIT
 * <p>
 * 한글 파일명(INSPIEN_홍길동_...)을 위해 제어 연결을 UTF-8 로 사용하고 서버에 OPTS UTF8 ON 을 요청한다.
 */
public final class SimpleFtpClient implements Closeable {

    private static final Pattern PASV = Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)");
    private static final Pattern EPSV = Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)");

    public record Reply(int code, String text) {
        boolean is(int... codes) {
            for (int c : codes) if (code == c) return true;
            return false;
        }
    }

    private final int timeoutMs;
    private final boolean useControlHostForPassive;
    private Socket control;
    private BufferedReader in;
    private Writer out;
    private String host;

    public SimpleFtpClient(int timeoutMs, boolean useControlHostForPassive) {
        this.timeoutMs = timeoutMs;
        this.useControlHostForPassive = useControlHostForPassive;
    }

    public void connect(String host, int port) {
        this.host = host;
        try {
            control = new Socket();
            control.connect(new InetSocketAddress(host, port), timeoutMs);
            control.setSoTimeout(timeoutMs);
            in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
            out = new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8);
            Reply r = readReply();
            if (r.code() == 120) r = readReply();
            if (r.code() != 220) {
                throw new EaiException(ErrorCode.FTP_CONNECT, "FTP 서버 응답 이상: " + r);
            }
        } catch (IOException e) {
            throw new EaiException(ErrorCode.FTP_CONNECT, "FTP 연결 실패 " + host + ":" + port + " - " + e.getMessage(), e);
        }
    }

    public void login(String user, String password) {
        Reply r = send("USER " + user, ErrorCode.FTP_LOGIN);
        if (r.code() == 331 || r.code() == 332) {
            r = send("PASS " + password, ErrorCode.FTP_LOGIN);
        }
        if (!r.is(230, 202)) {
            throw new EaiException(ErrorCode.FTP_LOGIN, "FTP 로그인 실패: " + r);
        }
        send("OPTS UTF8 ON", ErrorCode.FTP_CONNECT); // 미지원 서버면 무시
        expect(send("TYPE I", ErrorCode.FTP_CONNECT), ErrorCode.FTP_CONNECT, 200);
    }

    public void changeDirectory(String dir) {
        expect(send("CWD " + dir, ErrorCode.FTP_TRANSFER), ErrorCode.FTP_TRANSFER, 250, 200);
    }

    public void store(String fileName, byte[] content) {
        try (Socket data = openDataConnection()) {
            Reply r = send("STOR " + fileName, ErrorCode.FTP_TRANSFER);
            expect(r, ErrorCode.FTP_TRANSFER, 125, 150);
            OutputStream os = data.getOutputStream();
            os.write(content);
            os.flush();
            data.shutdownOutput();
        } catch (IOException e) {
            throw new EaiException(ErrorCode.FTP_TRANSFER, "FTP 데이터 전송 실패(" + fileName + "): " + e.getMessage(), e);
        }
        expect(read(ErrorCode.FTP_TRANSFER), ErrorCode.FTP_TRANSFER, 226, 250);
    }

    /** @return 파일 크기, 서버가 SIZE 를 지원하지 않으면 -1 */
    public long size(String fileName) {
        Reply r = send("SIZE " + fileName, ErrorCode.FTP_TRANSFER);
        if (r.code() != 213) return -1;
        try {
            return Long.parseLong(r.text().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void delete(String fileName) {
        expect(send("DELE " + fileName, ErrorCode.FTP_TRANSFER), ErrorCode.FTP_TRANSFER, 250, 200);
    }

    @Override
    public void close() {
        if (control == null) return;
        try {
            if (!control.isClosed()) {
                out.write("QUIT\r\n");
                out.flush();
            }
        } catch (IOException ignored) {
            // 종료 중 오류는 무시
        } finally {
            try {
                control.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------ internal

    private Socket openDataConnection() throws IOException {
        Reply r = send("PASV", ErrorCode.FTP_TRANSFER);
        String dataHost;
        int dataPort;
        if (r.code() == 227) {
            Matcher m = PASV.matcher(r.text());
            if (!m.find()) throw new EaiException(ErrorCode.FTP_TRANSFER, "PASV 응답 해석 실패: " + r);
            String replyHost = m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4);
            dataPort = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
            // NAT 뒤 서버는 내부 IP 를 알려주는 경우가 많아 기본은 제어 연결 호스트 사용
            dataHost = useControlHostForPassive || "0.0.0.0".equals(replyHost) ? host : replyHost;
        } else {
            r = send("EPSV", ErrorCode.FTP_TRANSFER);
            Matcher m = EPSV.matcher(r.text());
            if (r.code() != 229 || !m.find()) {
                throw new EaiException(ErrorCode.FTP_TRANSFER, "Passive 모드 진입 실패: " + r);
            }
            dataHost = host;
            dataPort = Integer.parseInt(m.group(1));
        }
        Socket data = new Socket();
        data.connect(new InetSocketAddress(dataHost, dataPort), timeoutMs);
        data.setSoTimeout(timeoutMs);
        return data;
    }

    private Reply send(String command, ErrorCode onError) {
        try {
            out.write(command + "\r\n");
            out.flush();
        } catch (IOException e) {
            throw new EaiException(onError, "FTP 명령 전송 실패(" + mask(command) + "): " + e.getMessage(), e);
        }
        return read(onError);
    }

    private Reply read(ErrorCode onError) {
        try {
            return readReply();
        } catch (IOException e) {
            throw new EaiException(onError, "FTP 응답 수신 실패: " + e.getMessage(), e);
        }
    }

    private Reply readReply() throws IOException {
        String line = in.readLine();
        if (line == null) throw new IOException("서버가 연결을 끊었습니다.");
        if (line.length() < 3) throw new IOException("잘못된 응답: " + line);
        int code = Integer.parseInt(line.substring(0, 3));
        StringBuilder text = new StringBuilder(line.length() > 4 ? line.substring(4) : "");
        if (line.length() > 3 && line.charAt(3) == '-') { // 여러 줄 응답
            String end = line.substring(0, 3) + " ";
            String next;
            do {
                next = in.readLine();
                if (next == null) throw new IOException("여러 줄 응답 중 연결 끊김");
                text.append('\n').append(next);
            } while (!next.startsWith(end));
        }
        return new Reply(code, text.toString());
    }

    private static void expect(Reply r, ErrorCode onError, int... codes) {
        if (!r.is(codes)) {
            throw new EaiException(onError, "FTP 예상치 못한 응답: " + r.code() + " " + r.text());
        }
    }

    private static String mask(String command) {
        return command.startsWith("PASS ") ? "PASS ****" : command;
    }
}
