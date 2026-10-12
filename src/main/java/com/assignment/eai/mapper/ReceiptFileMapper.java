package com.assignment.eai.mapper;

import com.assignment.eai.model.OrderRecord;
import com.assignment.eai.model.ReceiptFile;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ORDER_TB 레코드 → 회계 영수증 파일.
 * <pre>
 *  파일명 : INSPIEN_{참여자명(한글)}_{yyyyMMddHHmmss}.txt
 *  내용   : ORDER_ID^USER_ID^ITEM_ID^APPLICANT_KEY^NAME^ADDRESS^ITEM_NAME^PRICE\n  (필드·순서 고정, 행마다 \n)
 * </pre>
 * 주의: 파일 필드 순서는 테이블 컬럼 순서와 다르다.
 */
public final class ReceiptFileMapper {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String DELIMITER = "^";
    private static final String LINE_END = "\n";

    public static String fileName(String participantName, LocalDateTime time) {
        return "INSPIEN_" + participantName + "_" + time.format(TS) + ".txt";
    }

    public ReceiptFile map(List<OrderRecord> records, String participantName, LocalDateTime time, Charset charset) {
        StringBuilder sb = new StringBuilder();
        for (OrderRecord r : records) {
            sb.append(String.join(DELIMITER,
                    r.orderId(), r.userId(), r.itemId(), r.applicantKey(),
                    r.name(), r.address(), r.itemName(), r.price()))
              .append(LINE_END);
        }
        return new ReceiptFile(fileName(participantName, time), sb.toString().getBytes(charset), records.size());
    }
}
