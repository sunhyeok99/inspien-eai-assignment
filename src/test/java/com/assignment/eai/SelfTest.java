package com.assignment.eai;

import com.assignment.eai.common.AesDecryptor;
import com.assignment.eai.common.AppLog;
import com.assignment.eai.common.ConnectionInfo;
import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.common.IdGenerator;
import com.assignment.eai.common.Json;
import com.assignment.eai.common.MonitorLog;
import com.assignment.eai.flow.OrderInterfaceFlow;
import com.assignment.eai.flow.OrderResult;
import com.assignment.eai.flow.ShipmentBatchFlow;
import com.assignment.eai.mapper.OrderValidator;
import com.assignment.eai.mapper.OrderXmlParser;
import com.assignment.eai.mapper.OrderMapper;
import com.assignment.eai.mapper.ReceiptFileMapper;
import com.assignment.eai.model.OrderMessage;
import com.assignment.eai.model.OrderRecord;
import com.assignment.eai.model.SkippedItem;
import com.assignment.eai.receiver.FtpReceiptReceiver;
import com.assignment.eai.receiver.JdbcOrderReceiver;
import com.assignment.eai.receiver.JdbcShipmentReceiver;
import com.assignment.eai.receiver.JdbcSupport;
import com.assignment.eai.sender.RestOrderSender;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 외부 라이브러리 없이 실행하는 자체 테스트.
 * DB 는 인메모리 가짜 JDBC 드라이버, FTP 는 Python 최소 FTP 서버로 대체해 시나리오 1·2 를 끝까지 검증한다.
 * 실행: ./scripts/selftest.sh
 */
public final class SelfTest {

    static final String KEY = "TESTKEY01";
    static final String NAME = "홍길동";
    static int passed;
    static int failed;

    /** 과제 문서 예시 그대로 (루트 없음, STATUS 안에 줄바꿈/공백) */
    static final String SAMPLE = """
            <HEADER>
                <USER_ID>USER1</USER_ID>
                <NAME>홍길동</NAME>
                <ADDRESS>서울특별시 금천구</ADDRESS>
                <STATUS>
                    N
                </STATUS>
            </HEADER>
            <HEADER>
                <USER_ID>USER2</USER_ID>
                <NAME>유관순</NAME>
                <ADDRESS>서울특별시 구로구</ADDRESS>
                <STATUS>
                    N
                </STATUS>
            </HEADER>
            <ITEM>
                <USER_ID>USER1</USER_ID>
                <ITEM_ID>ITEM1</ITEM_ID>
                <ITEM_NAME>청바지</ITEM_NAME>
                <PRICE>21000</PRICE>
            </ITEM>
            <ITEM>
                <USER_ID>USER2</USER_ID>
                <ITEM_ID>ITEM2</ITEM_ID>
                <ITEM_NAME>티셔츠</ITEM_NAME>
                <PRICE>15800</PRICE>
            </ITEM>
            """;

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("eai-selftest");
        AppLog.init(work.resolve("logs"));
        MonitorLog.init(work.resolve("logs"));
        Class.forName(FakeJdbc.class.getName());

        unitTests();

        Path ftpRoot = Files.createDirectories(work.resolve("ftp"));
        int ftpPort = freePort();
        Process ftp = startFtp(ftpPort, ftpRoot, false);
        int badFtpPort = freePort();
        Process badFtp = startFtp(badFtpPort, Files.createDirectories(work.resolve("ftp-bad")), true);
        try {
            scenario1(ftpPort, badFtpPort, ftpRoot);
            scenario2();
            restSender(ftpPort);
            monitorLog(work.resolve("logs"));
        } finally {
            ftp.destroy();
            badFtp.destroy();
        }

        System.out.println("\n==== 결과: " + passed + " passed, " + failed + " failed ====");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ unit

    static void unitTests() {
        section("공통 / Mapper 단위 검증");

        AesDecryptor aes = new AesDecryptor("010-1234-5678");
        String json = "{\"HOST\":\"db.example.com\",\"PORT\":3306,\"USER\":\"u\",\"PASSWORD\":\"p\",\"DB\":\"shop\"}";
        check("AES 복호화 라운드트립", aes.decrypt(aes.encrypt(json)).equals(json));
        check("다른 전화번호 키로는 복호화 실패", throwsCode(() -> new AesDecryptor("010-0000-0000").decrypt(aes.encrypt(json)), ErrorCode.CONFIG));

        ConnectionInfo ci = new ConnectionInfo("order.db", Json.parseObject(json), null, "order.db");
        check("접속 정보로 JDBC URL 조립(포트로 MySQL 추정)", ci.jdbcUrl().startsWith("jdbc:mysql://db.example.com:3306/shop"));
        check("FTP 호스트를 URL 키로 줄 때 host 인식", "10.0.0.1".equals(new ConnectionInfo("ftp", Map.of("URL", "10.0.0.1", "PORT", "21"), null, "ftp").host()));
        check("ftp://host:port/path 형태 URL 에서 host 추출", "ftp.example.com".equals(new ConnectionInfo("ftp", Map.of("URL", "ftp://ftp.example.com:2121/a/"), null, "ftp").host()));
        check("JDBC URL 은 host 로 쓰지 않음", new ConnectionInfo("db", Map.of("URL", "jdbc:oracle:thin:@h:1521:ORCL"), null, "db").host() == null);

        check("채번 A999 다음은 B000", IdGenerator.fromIndex(IdGenerator.toIndex("A999") + 1).equals("B000"));
        check("기존 [A113,B114,잘못된값] 다음은 B115", IdGenerator.fromIndex(IdGenerator.nextIndex(List.of("A113", "B114", "XX"))).equals("B115"));
        check("Z999 이후 채번 불가", throwsCode(() -> IdGenerator.fromIndex(26000), ErrorCode.ID_EXHAUSTED));

        OrderMessage msg = new OrderXmlParser().parse(SAMPLE.getBytes(StandardCharsets.UTF_8), null);
        check("루트 없는 예시 XML 파싱: HEADER 2 / ITEM 2", msg.headers().size() == 2 && msg.items().size() == 2);
        check("STATUS 줄바꿈·공백 trim → 'N'", "N".equals(msg.headers().get(0).status()));

        byte[] euckr = ("<?xml version=\"1.0\" encoding=\"EUC-KR\"?><ORDER>" + SAMPLE + "</ORDER>").getBytes(Charset.forName("EUC-KR"));
        OrderMessage m2 = new OrderXmlParser().parse(euckr, null);
        check("EUC-KR 선언 XML 한글 복원", "홍길동".equals(m2.headers().get(0).name()) && "청바지".equals(m2.items().get(0).itemName()));

        String orphan = SAMPLE.replace("<USER_ID>USER2</USER_ID>\n    <ITEM_ID>", "<USER_ID>USER9</USER_ID>\n    <ITEM_ID>");
        OrderValidator.Result partial = validate(orphan);
        check("HEADER 없는 ITEM 만 제외하고 나머지는 처리 대상", partial.accepted().items().size() == 1
                && partial.accepted().items().get(0).itemId().equals("ITEM1")
                && partial.skipped().equals(List.of(new SkippedItem("USER9", "ITEM2", "USER_ID(USER9) 에 해당하는 HEADER 가 없습니다."))));
        check("제외 건에는 ORDER_ID 를 채번하지 않음", new OrderMapper().toRecords(partial.accepted(), KEY, 0).stream()
                .map(OrderRecord::orderId).toList().equals(List.of("A000")));
        check("모든 ITEM 이 HEADER 없으면 전체 거부", throwsCode(() -> validate(orphan.replace("<USER_ID>USER1</USER_ID>\n    <ITEM_ID>", "<USER_ID>USER8</USER_ID>\n    <ITEM_ID>")), ErrorCode.VALIDATION));
        check("HEADER 없는 ITEM 이 있어도 PRICE 오류가 있으면 전체 거부", throwsCode(() -> validate(orphan.replace("21000", "2만원")), ErrorCode.VALIDATION));
        check("HEADER 없는 ITEM 이 있어도 필수값 누락이면 전체 거부", throwsCode(() -> validate(orphan.replace("<ITEM_NAME>청바지</ITEM_NAME>", "")), ErrorCode.VALIDATION));
        check("제외 건 없으면 skipped 비어 있음", validate(SAMPLE).skipped().isEmpty());

        Map<String, Object> partialJson = OrderResult.ok("TX1", List.of("A000"), "f.txt", List.of(), partial.skipped()).toJson();
        check("일부 제외 응답: HTTP 200 / PARTIAL / 제외 건수·목록", OrderResult.ok("TX1", List.of("A000"), "f.txt", List.of(), partial.skipped()).httpStatus() == 200
                && "PARTIAL".equals(partialJson.get("result")) && "S001".equals(partialJson.get("code"))
                && Integer.valueOf(1).equals(partialJson.get("skippedCount"))
                && Json.write(partialJson).contains("\"skippedItems\":[{\"userId\":\"USER9\",\"itemId\":\"ITEM2\",\"reason\":"));
        Map<String, Object> okJson = OrderResult.ok("TX2", List.of("A000"), "f.txt", List.of(), List.of()).toJson();
        check("제외 없으면 SUCCESS, skippedItems 없음", "SUCCESS".equals(okJson.get("result")) && !okJson.containsKey("skippedItems"));
        check("PRICE 숫자 아니면 거부", throwsCode(() -> validate(SAMPLE.replace("21000", "2만원")), ErrorCode.VALIDATION));
        check("값에 구분자 ^ 있으면 거부", throwsCode(() -> validate(SAMPLE.replace("청바지", "청^바지")), ErrorCode.VALIDATION));
        check("XML 형식 오류 거부", throwsCode(() -> validate("<HEADER><USER_ID>U</HEADER>"), ErrorCode.VALIDATION));
        check("DTD(XXE) 거부", throwsCode(() -> validate("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><HEADER/>"), ErrorCode.VALIDATION));

        List<OrderRecord> records = new OrderMapper().toRecords(msg, KEY, IdGenerator.toIndex("A113"));
        check("HEADER:ITEM → 플랫 2행, ORDER_ID A113/A114", records.size() == 2
                && records.get(0).orderId().equals("A113") && records.get(1).orderId().equals("A114")
                && records.get(1).name().equals("유관순"));
        String content = new String(new ReceiptFileMapper().map(records, NAME, LocalDateTime.of(2026, 10, 13, 9, 5, 7),
                StandardCharsets.UTF_8).content(), StandardCharsets.UTF_8);
        String expected = "A113^USER1^ITEM1^" + KEY + "^홍길동^서울특별시 금천구^청바지^21000\n"
                + "A114^USER2^ITEM2^" + KEY + "^유관순^서울특별시 구로구^티셔츠^15800\n";
        check("영수증 파일 내용/필드 순서", content.equals(expected));
        check("영수증 파일명 규칙", ReceiptFileMapper.fileName(NAME, LocalDateTime.of(2026, 10, 13, 9, 5, 7))
                .equals("INSPIEN_홍길동_20261013090507.txt"));
    }

    static OrderValidator.Result validate(String xml) {
        return new OrderValidator().validate(new OrderXmlParser().parse(xml.getBytes(StandardCharsets.UTF_8), null));
    }

    // ------------------------------------------------------------------ scenario 1

    static OrderInterfaceFlow orderFlow(int ftpPort) {
        JdbcSupport jdbc = new JdbcSupport(2, 10, 3);
        FtpReceiptReceiver ftp = new FtpReceiptReceiver(ftpConn(ftpPort), 2, 10, 3, true);
        return new OrderInterfaceFlow(KEY, NAME, StandardCharsets.UTF_8, jdbc, conn("order"),
                new JdbcOrderReceiver("ORDER_TB"), ftp, LocalDateTime::now);
    }

    static void scenario1(int ftpPort, int badFtpPort, Path ftpRoot) throws Exception {
        section("시나리오 1: 주문 실시간 처리");
        FakeJdbc.reset();
        // 다른 지원자 데이터가 섞여 있는 공유 테이블 가정
        FakeJdbc.table("order", "ORDER_TB").add(new java.util.LinkedHashMap<>(Map.of(
                "ORDER_ID", "Z500", "APPLICANT_KEY", "OTHER", "STATUS", "N", "ITEM_ID", "X", "ADDRESS", "X")));

        OrderInterfaceFlow flow = orderFlow(ftpPort);
        OrderResult r = flow.process(SAMPLE.getBytes(StandardCharsets.UTF_8), null);
        check("정상 처리 성공 응답", r.success() && r.httpStatus() == 200);
        check("다른 지원자 ID 와 무관하게 A000 부터 채번", r.orderIds().equals(List.of("A000", "A001")));
        List<Map<String, String>> mine = mine(FakeJdbc.table("order", "ORDER_TB"));
        check("ORDER_TB 2건 적재 (STATUS=N)", mine.size() == 2 && mine.stream().allMatch(x -> "N".equals(x.get("STATUS"))));
        Path uploaded = ftpRoot.resolve(r.fileName());
        check("FTP 에 한글 파일명으로 업로드", Files.exists(uploaded) && r.fileName().startsWith("INSPIEN_홍길동_"));
        check("업로드 파일 내용", Files.readString(uploaded).startsWith("A000^USER1^ITEM1^" + KEY + "^홍길동^"));

        OrderResult r2 = flow.process(SAMPLE.getBytes(StandardCharsets.UTF_8), null);
        check("두 번째 요청은 A002 부터, 파일명 겹치지 않음", r2.orderIds().equals(List.of("A002", "A003")) && !r2.fileName().equals(r.fileName()));

        // FTP 실패 → DB 롤백
        int before = mine(FakeJdbc.table("order", "ORDER_TB")).size();
        OrderResult bad = orderFlow(badFtpPort).process(SAMPLE.getBytes(StandardCharsets.UTF_8), null);
        check("FTP 전송 실패 → 실패 응답(E420)", !bad.success() && bad.code().equals("E420"));
        check("FTP 실패 시 ORDER_TB 롤백(건수 변화 없음)", mine(FakeJdbc.table("order", "ORDER_TB")).size() == before);

        // COMMIT 실패 → FTP 파일 삭제(보상)
        Set<String> filesBefore = listFiles(ftpRoot);
        FakeJdbc.failCommit = true;
        OrderResult cf = flow.process(SAMPLE.getBytes(StandardCharsets.UTF_8), null);
        FakeJdbc.failCommit = false;
        check("COMMIT 실패 → 실패 응답", !cf.success());
        check("COMMIT 실패 시 업로드한 영수증 삭제(보상)", listFiles(ftpRoot).equals(filesBefore));
        check("COMMIT 실패 시 ORDER_TB 미반영", mine(FakeJdbc.table("order", "ORDER_TB")).size() == before);

        // DB 연결 실패
        FakeJdbc.failConnect = true;
        OrderResult down = flow.process(SAMPLE.getBytes(StandardCharsets.UTF_8), null);
        FakeJdbc.failConnect = false;
        check("DB 연결 실패 → 503(E300), FTP 미전송", down.httpStatus() == 503 && down.code().equals("E300")
                && listFiles(ftpRoot).equals(filesBefore));

        // 검증 실패
        OrderResult invalid = flow.process(SAMPLE.replace("21000", "abc").getBytes(StandardCharsets.UTF_8), null);
        check("검증 실패 → 400(E100)", invalid.httpStatus() == 400 && invalid.code().equals("E100"));

        // 동시 요청
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<OrderResult>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) fs.add(pool.submit(() -> flow.process(SAMPLE.getBytes(StandardCharsets.UTF_8), null)));
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        boolean allOk = true;
        for (Future<OrderResult> f : fs) {
            OrderResult x = f.get();
            allOk &= x.success();
            ids.addAll(x.orderIds());
            names.add(x.fileName());
        }
        pool.shutdown();
        check("동시 요청 4건: 모두 성공, ORDER_ID 8개 모두 다름, 파일명 4개 모두 다름", allOk && ids.size() == 8 && names.size() == 4);
    }

    // ------------------------------------------------------------------ scenario 2

    static ShipmentBatchFlow batchFlow() {
        return new ShipmentBatchFlow(KEY, new JdbcSupport(2, 10, 3), conn("order"), conn("ship"),
                new JdbcOrderReceiver("ORDER_TB"), new JdbcShipmentReceiver("SHIPMENT_TB"));
    }

    static void scenario2() {
        section("시나리오 2: 운송사 전송 배치");
        int pending = (int) mine(FakeJdbc.table("order", "ORDER_TB")).stream().filter(x -> "N".equals(x.get("STATUS"))).count();

        ShipmentBatchFlow.BatchResult r = batchFlow().run();
        check("미전송 " + pending + "건 모두 운송사 적재", r.total() == pending && r.sent() == pending && r.failed() == 0);
        check("다른 지원자 주문(Z500)은 대상 아님", FakeJdbc.table("ship", "SHIPMENT_TB").stream().noneMatch(x -> "Z500".equals(x.get("ORDER_ID"))));
        check("적재 성공 건 STATUS=Y", mine(FakeJdbc.table("order", "ORDER_TB")).stream().allMatch(x -> "Y".equals(x.get("STATUS"))));
        check("SHIPMENT_ID A000 부터 채번", "A000".equals(FakeJdbc.table("ship", "SHIPMENT_TB").get(0).get("SHIPMENT_ID")));

        check("재실행 시 대상 0건 (중복 전송 없음)", batchFlow().run().total() == 0);

        // 새 주문 2건 중 1건은 STATUS UPDATE 실패 상황
        FakeJdbc.table("order", "ORDER_TB").add(row("B001", "ITEM1"));
        FakeJdbc.table("order", "ORDER_TB").add(row("B002", "ITEM2"));
        FakeJdbc.failUpdateFor.add("B002");
        ShipmentBatchFlow.BatchResult p = batchFlow().run();
        check("STATUS UPDATE 실패 건만 실패, 나머지는 성공(PARTIAL)", p.sent() == 1 && p.failed() == 1
                && p.status() == MonitorLog.Status.PARTIAL);
        // 주의: B002 는 SHIPMENT INSERT 는 됐지만 STATUS 가 N 으로 남은 상태
        FakeJdbc.failUpdateFor.clear();
        int shipCount = FakeJdbc.table("ship", "SHIPMENT_TB").size();
        ShipmentBatchFlow.BatchResult rec = batchFlow().run();
        check("다음 주기: 이미 적재된 B002 는 중복 INSERT 없이 STATUS 만 보정(멱등)", rec.total() == 1 && rec.recovered() == 1
                && FakeJdbc.table("ship", "SHIPMENT_TB").size() == shipCount);

        FakeJdbc.table("order", "ORDER_TB").add(row("B003", "ITEM3"));
        FakeJdbc.failConnect = true;
        ShipmentBatchFlow.BatchResult down = batchFlow().run();
        FakeJdbc.failConnect = false;
        check("DB 연결 실패 → 배치 FAIL 결과 반환(예외로 스케줄러를 죽이지 않음)", down.status() == MonitorLog.Status.FAIL);
        check("연결 복구 후 다음 주기에 B003 처리", batchFlow().run().sent() == 1);
    }

    static Map<String, String> row(String id, String item) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("ORDER_ID", id);
        m.put("APPLICANT_KEY", KEY);
        m.put("ITEM_ID", item);
        m.put("ADDRESS", "서울");
        m.put("STATUS", " N "); // 공백이 섞여 저장된 경우도 대상이 되어야 함
        return m;
    }

    // ------------------------------------------------------------------ REST

    static void restSender(int ftpPort) throws Exception {
        section("REST Sender (HTTP)");
        int port = freePort();
        RestOrderSender sender = new RestOrderSender(orderFlow(ftpPort), port);
        sender.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> ok = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
                    .header("Content-Type", "application/xml; charset=EUC-KR")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(SAMPLE.getBytes(Charset.forName("EUC-KR")))).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> body = Json.parseObject(ok.body());
            System.out.println("    응답 예시: " + ok.body());
            check("POST /api/orders (EUC-KR) → 200 SUCCESS JSON", ok.statusCode() == 200 && "SUCCESS".equals(body.get("result")));

            HttpResponse<String> bad = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
                    .header("Content-Type", "application/xml")
                    .POST(HttpRequest.BodyPublishers.ofString("<HEADER>")).build(), HttpResponse.BodyHandlers.ofString());
            check("잘못된 XML → 400 FAIL JSON", bad.statusCode() == 400 && bad.body().contains("\"result\":\"FAIL\""));

            HttpResponse<String> get = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check("GET → 405", get.statusCode() == 405);
        } finally {
            sender.stop();
        }
    }

    static void monitorLog(Path logDir) throws Exception {
        section("모니터링 로그");
        Path dir = logDir.resolve("monitor");
        List<String> lines = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) lines.addAll(Files.readAllLines(p));
        }
        check("요청 단위 처리 이력이 JSON Lines 로 기록", !lines.isEmpty() && lines.stream().allMatch(l -> l.startsWith("{\"time\"")));
        check("실패 이력에 errorCode 포함", lines.stream().anyMatch(l -> l.contains("\"status\":\"FAIL\"") && l.contains("\"errorCode\"")));
        check("보상 처리 이력 기록", lines.stream().anyMatch(l -> l.contains("COMPENSATED")));
        try (var s = Files.list(logDir.resolve("error-payload"))) {
            check("실패 원본 메시지 보관(재처리용)", s.findAny().isPresent());
        }
        System.out.println("    로그 예시: " + lines.get(0));
        System.out.println("    로그 예시: " + lines.stream().filter(l -> l.contains("FAIL")).findFirst().orElse(""));
    }

    // ------------------------------------------------------------------ helpers

    static ConnectionInfo conn(String db) {
        return new ConnectionInfo(db, Map.of("url", "jdbc:fake:" + db, "user", "u", "password", "p"), null, db);
    }

    static ConnectionInfo ftpConn(int port) {
        return new ConnectionInfo("ftp", Map.of("host", "127.0.0.1", "port", (long) port, "user", "test", "password", "test"), null, "ftp");
    }

    static List<Map<String, String>> mine(List<Map<String, String>> rows) {
        synchronized (FakeJdbc.DB) {
            return rows.stream().filter(x -> KEY.equals(x.get("APPLICANT_KEY"))).toList();
        }
    }

    static Set<String> listFiles(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return new HashSet<>(s.map(p -> p.getFileName().toString()).toList());
        }
    }

    static Process startFtp(int port, Path root, boolean failStor) throws Exception {
        Path script = Path.of("src/test/resources/mini_ftp_server.py");
        List<String> cmd = new ArrayList<>(List.of("python3", script.toString(), String.valueOf(port), root.toString()));
        if (failStor) cmd.add("--fail-stor");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String line = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
        if (!"ready".equals(line)) throw new IllegalStateException("FTP 테스트 서버 기동 실패: " + line);
        return p;
    }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static boolean throwsCode(Runnable r, ErrorCode code) {
        try {
            r.run();
            return false;
        } catch (EaiException e) {
            return e.errorCode() == code;
        }
    }

    static void section(String name) {
        System.out.println("\n## " + name);
    }

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else failed++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name);
    }
}
