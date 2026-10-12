package com.assignment.eai.receiver;

import com.assignment.eai.common.AppLog;
import com.assignment.eai.common.ConnectionInfo;
import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.common.Retry;
import com.assignment.eai.model.ReceiptFile;

import java.util.logging.Logger;

/**
 * 회계 담당자 FTP 어댑터.
 * 업로드는 같은 파일명으로 덮어쓰기라 재시도해도 결과가 같다(멱등) → 일시적 장애 시 재시도.
 * 업로드 후 SIZE 로 서버 파일 크기를 확인해 전송 누락을 검출한다.
 */
public final class FtpReceiptReceiver {

    private static final Logger log = AppLog.get(FtpReceiptReceiver.class);

    private final ConnectionInfo info;
    private final int maxAttempts;
    private final long backoffMs;
    private final int timeoutMs;
    private final boolean useControlHost;

    public FtpReceiptReceiver(ConnectionInfo info, int maxAttempts, long backoffMs, int timeoutSeconds, boolean useControlHost) {
        this.info = info;
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
        this.timeoutMs = timeoutSeconds * 1000;
        this.useControlHost = useControlHost;
    }

    public void upload(ReceiptFile file) {
        Retry.call("FTP 업로드(" + file.fileName() + ")", maxAttempts, backoffMs, () -> {
            try (SimpleFtpClient ftp = open()) {
                ftp.store(file.fileName(), file.content());
                long size = ftp.size(file.fileName());
                if (size >= 0 && size != file.content().length) {
                    throw new EaiException(ErrorCode.FTP_TRANSFER,
                            "업로드 파일 크기 불일치: 보낸 " + file.content().length + " / 서버 " + size);
                }
                log.info("FTP 업로드 완료: " + file.fileName() + " (" + file.content().length + " bytes, " + file.lineCount() + "건)");
                return null;
            }
        });
    }

    /** 보상 처리: DB 반영이 실패했을 때 이미 올린 영수증을 삭제 */
    public void delete(String fileName) {
        Retry.call("FTP 파일 삭제(" + fileName + ")", maxAttempts, backoffMs, () -> {
            try (SimpleFtpClient ftp = open()) {
                ftp.delete(fileName);
                return null;
            }
        });
    }

    public void check() {
        try (SimpleFtpClient ftp = open()) {
            log.info("FTP 접속 확인 완료: " + info.host());
        }
    }

    private SimpleFtpClient open() {
        String host = info.host();
        if (host == null) {
            throw new EaiException(ErrorCode.CONFIG, "FTP host 정보가 없습니다: " + info.masked());
        }
        SimpleFtpClient ftp = new SimpleFtpClient(timeoutMs, useControlHost);
        try {
            ftp.connect(host, info.port(21));
            ftp.login(info.user(), info.password());
            String dir = info.remoteDir();
            if (dir != null && !dir.isBlank() && !"/".equals(dir)) {
                ftp.changeDirectory(dir);
            }
            return ftp;
        } catch (RuntimeException e) {
            ftp.close();
            throw e;
        }
    }
}
