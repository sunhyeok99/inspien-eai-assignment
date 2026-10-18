package com.assignment.eai.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 모니터링 로그(JSON Lines) 집계 - stats 명령.
 * <pre>
 *  IF_ORDER_001 : 요청 건수(END 기준 성공/부분/실패), 적재 건수, 제외 ITEM 수, 실패 오류 코드별 건수
 *  IF_SHIP_001  : 배치 실행 횟수, 전송/복구/실패 건수, 대상 없음 횟수
 *  미종료        : 시작 이력은 있는데 END 가 없는 txId (처리 중이거나 비정상 종료 → 확인 필요)
 * </pre>
 * 파일 읽기와 분리해 두어 로그 줄 목록만으로 테스트할 수 있다.
 */
public final class MonitorStats {

    private static final String ORDER_IF = "IF_ORDER_001";
    private static final String SHIP_IF = "IF_SHIP_001";

    private final Map<String, Integer> orderEnd = new LinkedHashMap<>();
    private final Map<String, Integer> orderFailCodes = new TreeMap<>();
    private int ordersLoaded;
    private int itemsSkipped;

    private final Map<String, Integer> shipEnd = new LinkedHashMap<>();
    private int shipSent;
    private int shipRecovered;
    private int shipFailed;

    private final Set<String> started = new LinkedHashSet<>();
    private final Set<String> ended = new LinkedHashSet<>();
    private int badLines;

    public static MonitorStats of(List<String> lines) {
        MonitorStats s = new MonitorStats();
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                s.add(Json.parseObject(line));
            } catch (IllegalArgumentException e) {
                s.badLines++;
            }
        }
        return s;
    }

    private void add(Map<String, Object> m) {
        String txId = str(m.get("txId"));
        String itf = str(m.get("interfaceId"));
        String step = str(m.get("step"));
        String status = str(m.get("status"));

        if ("START".equals(status)) started.add(txId);
        if (!"END".equals(step)) {
            if (ORDER_IF.equals(itf) && "SKIP".equals(status)) itemsSkipped++;
            if (SHIP_IF.equals(itf) && "SHIPMENT_INSERT".equals(step)) {
                if ("SUCCESS".equals(status)) shipSent++;
                if ("FAIL".equals(status)) shipFailed++;
            }
            if (SHIP_IF.equals(itf) && "SHIPMENT_RECOVER".equals(step) && "SUCCESS".equals(status)) shipRecovered++;
            return;
        }

        ended.add(txId);
        if (ORDER_IF.equals(itf)) {
            orderEnd.merge(status, 1, Integer::sum);
            if ("FAIL".equals(status)) {
                orderFailCodes.merge(m.get("errorCode") == null ? "-" : str(m.get("errorCode")), 1, Integer::sum);
            } else {
                ordersLoaded += m.get("count") instanceof Number n ? n.intValue() : 0;
            }
        } else if (SHIP_IF.equals(itf)) {
            shipEnd.merge(status, 1, Integer::sum);
        }
    }

    public int orderRequests() { return sum(orderEnd); }
    public int orderCount(String status) { return orderEnd.getOrDefault(status, 0); }
    public int ordersLoaded() { return ordersLoaded; }
    public int itemsSkipped() { return itemsSkipped; }
    public Map<String, Integer> orderFailCodes() { return orderFailCodes; }

    public int batchRuns() { return sum(shipEnd); }
    public int batchCount(String status) { return shipEnd.getOrDefault(status, 0); }
    public int shipSent() { return shipSent; }
    public int shipRecovered() { return shipRecovered; }
    public int shipFailed() { return shipFailed; }

    /** 시작은 했는데 END 가 없는 txId */
    public List<String> unfinished() {
        List<String> list = new ArrayList<>(started);
        list.removeAll(ended);
        return list;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] 요청 %d건: 성공 %d / 부분 %d / 실패 %d | 적재 %d건, 제외 ITEM %d건%n",
                ORDER_IF, orderRequests(), orderCount("SUCCESS"), orderCount("PARTIAL"), orderCount("FAIL"),
                ordersLoaded, itemsSkipped));
        if (!orderFailCodes.isEmpty()) {
            sb.append("               실패 코드: ");
            orderFailCodes.forEach((code, n) -> sb.append(code).append(' ').append(n).append("건 "));
            sb.append(System.lineSeparator());
        }
        sb.append(String.format("[%s]  배치 %d회: 전송 %d건 / 복구 %d건 / 실패 %d건 (대상 없음 %d회, 배치 오류 %d회)%n",
                SHIP_IF, batchRuns(), shipSent, shipRecovered, shipFailed, batchCount("SKIP"), batchCount("FAIL")));
        List<String> open = unfinished();
        if (open.isEmpty()) {
            sb.append("미종료 0건");
        } else {
            sb.append("미종료 ").append(open.size()).append("건 (처리 중이거나 비정상 종료, 확인 필요)");
            open.forEach(t -> sb.append(System.lineSeparator()).append("  - ").append(t));
        }
        if (badLines > 0) {
            sb.append(System.lineSeparator()).append("해석하지 못한 줄 ").append(badLines).append("건");
        }
        return sb.toString();
    }

    private static int sum(Map<String, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
