package com.assignment.eai.model;

/** 회계 담당자에게 FTP 로 보낼 영수증 파일 */
public record ReceiptFile(String fileName, byte[] content, int lineCount) {
}
