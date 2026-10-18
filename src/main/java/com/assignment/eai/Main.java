package com.assignment.eai;

import com.assignment.eai.bootstrap.ApplicantInfoClient;
import com.assignment.eai.common.AppConfig;
import com.assignment.eai.common.AppLog;
import com.assignment.eai.common.ConnectionInfo;
import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.MonitorLog;
import com.assignment.eai.common.MonitorStats;
import com.assignment.eai.flow.OrderInterfaceFlow;
import com.assignment.eai.flow.ShipmentBatchFlow;
import com.assignment.eai.receiver.FtpReceiptReceiver;
import com.assignment.eai.receiver.JdbcOrderReceiver;
import com.assignment.eai.receiver.JdbcShipmentReceiver;
import com.assignment.eai.receiver.JdbcSupport;
import com.assignment.eai.sender.RestOrderSender;
import com.assignment.eai.sender.ShipmentBatchScheduler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;

/**
 * 진입점.
 * <pre>
 *  init  --name 홍길동 --phone 010-1234-5678 --email a@b.com   정보 수신 + 접속 정보 복호화
 *  check                                                         DB 2개 / FTP 접속 확인
 *  server                                                        REST Sender + 5분 배치 기동 (시나리오 1, 2)
 *  send  [xml파일] [url]                                         주문 요청 전송 (기본: 수신한 샘플 XML)
 *  batch                                                         배치 1회 즉시 실행 (시나리오 2)
 *  report                                                        내 지원자 키 기준 ORDER_TB / SHIPMENT_TB 조회
 *  stats [yyyyMMdd]                                              모니터링 로그 처리 현황 요약 (기본: 오늘)
 * </pre>
 */
public final class Main {

    public static void main(String[] args) {
        String cmd = args.length == 0 ? "help" : args[0];
        try {
            switch (cmd) {
                case "init" -> init(args);
                case "check" -> check();
                case "server" -> server();
                case "send" -> send(args);
                case "batch" -> batch();
                case "report" -> report();
                case "stats" -> stats(args);
                default -> help();
            }
        } catch (EaiException e) {
            System.err.println("[" + e.errorCode().code() + " " + e.errorCode().description() + "] " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("오류: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void help() {
        System.out.println("""
                사용법: java -jar eai-assignment.jar <command>
                  init --name <이름> --phone <010-1234-5678> --email <이메일>   정보 수신 및 접속 정보 복호화
                  check                         DB / FTP 접속 확인
                  server                        REST Sender(POST /api/orders) + 5분 주기 배치 기동
                  send [xml파일] [url]          주문 요청 전송 (기본: config/received/sample.xml)
                  batch                         운송사 전송 배치 1회 실행
                  report                        ORDER_TB / SHIPMENT_TB 조회 (내 지원자 키 기준)
                  stats [yyyyMMdd]              모니터링 로그 처리 현황 요약 (기본: 오늘)
                """);
    }

    // ------------------------------------------------------------------ commands

    private static void init(String[] args) throws Exception {
        String name = option(args, "--name");
        String phone = option(args, "--phone");
        String email = option(args, "--email");
        if (name == null || phone == null || email == null) {
            throw new IllegalArgumentException("init --name <이름> --phone <010-1234-5678> --email <이메일>");
        }
        new ApplicantInfoClient().run(name, phone, email);
    }

    private static void check() {
        AppConfig cfg = boot();
        JdbcSupport jdbc = jdbc(cfg);
        for (ConnectionInfo info : new ConnectionInfo[]{cfg.orderDb(), cfg.shipmentDb()}) {
            System.out.println(info.name() + " → " + info.jdbcUrl());
            try (Connection c = jdbc.open(info)) {
                System.out.println("  OK " + c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
            } catch (Exception e) {
                System.out.println("  FAIL " + e.getMessage());
            }
        }
        System.out.println("ftp → " + cfg.ftp().host() + ":" + cfg.ftp().port(21));
        try {
            ftp(cfg).check();
            System.out.println("  OK");
        } catch (Exception e) {
            System.out.println("  FAIL " + e.getMessage());
        }
    }

    private static void server() throws Exception {
        AppConfig cfg = boot();
        RestOrderSender rest = new RestOrderSender(orderFlow(cfg), cfg.serverPort(),
                cfg.serverThreads(), cfg.serverMaxBodyBytes());
        ShipmentBatchScheduler scheduler = new ShipmentBatchScheduler(shipmentFlow(cfg),
                cfg.batchInitialDelaySeconds(), cfg.batchIntervalSeconds());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            rest.stop();
            scheduler.stop();
            stopped.countDown();
        }));
        rest.start();
        scheduler.start();
        System.out.println("기동 완료. 종료: Ctrl+C");
        stopped.await();
    }

    private static void send(String[] args) throws Exception {
        Path file = Path.of(args.length > 1 ? args[1] : ApplicantInfoClient.SAMPLE_XML.toString());
        String url = args.length > 2 ? args[2] : "http://localhost:" + portOrDefault() + "/api/orders";
        byte[] body = Files.readAllBytes(file);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        System.out.println("HTTP " + res.statusCode());
        System.out.println(res.body());
    }

    private static void batch() {
        AppConfig cfg = boot();
        System.out.println(shipmentFlow(cfg).run().summary());
    }

    private static void stats(String[] args) throws Exception {
        AppConfig cfg = boot();
        String day = args.length > 1 ? args[1] : LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Path file = cfg.logDir().resolve("monitor").resolve("monitor-" + day + ".log");
        if (!Files.exists(file)) {
            System.out.println("모니터링 로그가 없습니다: " + file);
            return;
        }
        System.out.println("모니터링 로그: " + file);
        System.out.println(MonitorStats.of(Files.readAllLines(file, StandardCharsets.UTF_8)).render());
    }

    private static void report() throws Exception {
        AppConfig cfg = boot();
        JdbcSupport jdbc = jdbc(cfg);
        System.out.println("APPLICANT_KEY = " + cfg.applicantKey() + " / 조회 시각 " + LocalDateTime.now());
        printTable(jdbc, cfg.orderDb(), cfg.orderTable(), cfg.applicantKey(), "ORDER_ID");
        printTable(jdbc, cfg.shipmentDb(), cfg.shipmentTable(), cfg.applicantKey(), "SHIPMENT_ID");
    }

    // ------------------------------------------------------------------ wiring

    private static AppConfig boot() {
        AppConfig cfg = AppConfig.load();
        AppLog.init(cfg.logDir());
        MonitorLog.init(cfg.logDir());
        return cfg;
    }

    private static JdbcSupport jdbc(AppConfig cfg) {
        return new JdbcSupport(cfg.retryMaxAttempts(), cfg.retryBackoffMs(), cfg.connectTimeoutSeconds());
    }

    private static FtpReceiptReceiver ftp(AppConfig cfg) {
        return new FtpReceiptReceiver(cfg.ftp(), cfg.retryMaxAttempts(), cfg.retryBackoffMs(),
                cfg.connectTimeoutSeconds(), cfg.ftpUseControlHostForPassive());
    }

    private static OrderInterfaceFlow orderFlow(AppConfig cfg) {
        return new OrderInterfaceFlow(cfg.applicantKey(), cfg.applicantName(), cfg.receiptCharset(), jdbc(cfg),
                cfg.orderDb(), new JdbcOrderReceiver(cfg.orderTable(), cfg.queryTimeoutSeconds()), ftp(cfg), LocalDateTime::now,
                cfg.duplicateRetry());
    }

    private static ShipmentBatchFlow shipmentFlow(AppConfig cfg) {
        return new ShipmentBatchFlow(cfg.applicantKey(), jdbc(cfg), cfg.orderDb(), cfg.shipmentDb(),
                new JdbcOrderReceiver(cfg.orderTable(), cfg.queryTimeoutSeconds()),
                new JdbcShipmentReceiver(cfg.shipmentTable(), cfg.queryTimeoutSeconds()));
    }

    // ------------------------------------------------------------------ util

    private static void printTable(JdbcSupport jdbc, ConnectionInfo info, String table, String key, String orderBy) throws Exception {
        System.out.println("\n== " + table);
        try (Connection c = jdbc.open(info);
             PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table + " WHERE APPLICANT_KEY = ? ORDER BY " + orderBy)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                StringBuilder head = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) head.append(i > 1 ? " | " : "").append(md.getColumnLabel(i));
                System.out.println(head);
                int n = 0;
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.append(i > 1 ? " | " : "").append(rs.getString(i));
                    System.out.println(row);
                    n++;
                }
                System.out.println("(" + n + "건)");
            }
        }
    }

    private static int portOrDefault() {
        try {
            return AppConfig.load().serverPort();
        } catch (RuntimeException e) {
            return 8080;
        }
    }

    private static String option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return null;
    }
}
