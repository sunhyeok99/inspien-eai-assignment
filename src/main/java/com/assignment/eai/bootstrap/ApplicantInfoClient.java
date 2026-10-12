package com.assignment.eai.bootstrap;

import com.assignment.eai.common.AesDecryptor;
import com.assignment.eai.common.AppConfig;
import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.common.Json;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 과제 4.1 "제공 정보 수신" 단계.
 * <ol>
 *   <li>정보 수신 API 호출 (Basic Auth, JSON: NAME / PHONE_NUMBER / E_MAIL)</li>
 *   <li>APPLICANT_KEY 저장</li>
 *   <li>ORDER_TB_CONN / SHIPMENT_TB_CONN / FTP_CONN 복호화 (AES-128/ECB/PKCS5, 키 = SHA-1(휴대폰번호) 앞 16byte)</li>
 *   <li>SAMPLE_DATA: Base64 → EUC-KR 바이트 → 문자열 로 복원해 샘플 요청 XML 로 저장</li>
 * </ol>
 * 결과는 모두 Git 에서 제외되는 config/ 하위 파일로 저장한다.
 */
public final class ApplicantInfoClient {

    public static final Path API_PROPERTIES = AppConfig.CONFIG_DIR.resolve("recruiting-api.properties");
    public static final Path RECEIVED_DIR = AppConfig.CONFIG_DIR.resolve("received");
    public static final Path SAMPLE_XML = RECEIVED_DIR.resolve("sample.xml");
    private static final Pattern PHONE = Pattern.compile("^010-\\d{4}-\\d{4}$");
    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    public void run(String name, String phone, String email) throws IOException, InterruptedException {
        if (!PHONE.matcher(phone).matches()) {
            throw new EaiException(ErrorCode.VALIDATION, "전화번호는 010-1234-5678 형식이어야 합니다: " + phone);
        }
        Properties api = loadApiProperties();

        // 1. 정보 수신 API 호출
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("NAME", name);
        body.put("PHONE_NUMBER", phone);
        body.put("E_MAIL", email);
        String auth = Base64.getEncoder().encodeToString(
                (api.getProperty("username").trim() + ":" + api.getProperty("password").trim()).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(api.getProperty("url").trim()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        System.out.println("정보 수신 API 호출: " + request.uri());
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        Files.createDirectories(RECEIVED_DIR);
        Files.writeString(RECEIVED_DIR.resolve("response.json"), response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() != 200) {
            throw new EaiException(ErrorCode.CONFIG, "정보 수신 실패 HTTP " + response.statusCode() + ": " + response.body());
        }
        Map<String, Object> res = Json.parseObject(response.body());

        // 2. 지원자 키
        String applicantKey = String.valueOf(find(res, "APPLICANT_KEY"));
        System.out.println("APPLICANT_KEY = " + applicantKey);

        // 3. 접속 정보 복호화
        AesDecryptor decryptor = new AesDecryptor(phone);
        Files.createDirectories(AppConfig.CONN_DIR);
        saveConn(decryptor, find(res, "ORDER_TB_CONN"), AppConfig.ORDER_DB_FILE);
        saveConn(decryptor, find(res, "SHIPMENT_TB_CONN"), AppConfig.SHIPMENT_DB_FILE);
        saveConn(decryptor, find(res, "FTP_CONN"), AppConfig.FTP_FILE);

        // 4. 샘플 데이터 (Base64 → EUC-KR)
        Object sample = find(res, "SAMPLE_DATA");
        if (sample != null) {
            byte[] raw = Base64.getMimeDecoder().decode(String.valueOf(sample));
            Files.write(RECEIVED_DIR.resolve("sample-euckr.xml"), raw);
            String xml = new String(raw, EUC_KR);
            // 파일은 UTF-8 로 저장하므로 XML 선언의 encoding 도 맞춰 준다
            xml = xml.replaceFirst("(<\\?xml[^>]*encoding\\s*=\\s*[\"'])[^\"']*([\"'])", "$1UTF-8$2");
            Files.writeString(SAMPLE_XML, xml, StandardCharsets.UTF_8);
            System.out.println("샘플 요청 XML 저장: " + SAMPLE_XML);
        }

        writeApplicationProperties(applicantKey, name);
        System.out.println("설정 저장 완료: " + AppConfig.PROPERTIES + " (Git 제외 대상)");
        System.out.println("다음 단계: check 명령으로 DB/FTP 접속을 확인하세요.");
    }

    private Properties loadApiProperties() throws IOException {
        if (!Files.exists(API_PROPERTIES)) {
            throw new EaiException(ErrorCode.CONFIG, API_PROPERTIES + " 가 없습니다. recruiting-api.example.properties 를 복사해 "
                    + "과제 문서의 호출 주소/username/password 를 입력하세요.");
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(API_PROPERTIES, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        for (String k : new String[]{"url", "username", "password"}) {
            if (p.getProperty(k) == null || p.getProperty(k).isBlank()) {
                throw new EaiException(ErrorCode.CONFIG, API_PROPERTIES + " 에 " + k + " 값이 없습니다.");
            }
        }
        return p;
    }

    /** 응답 키를 대소문자 무시하고 찾는다 */
    private static Object find(Map<String, Object> map, String key) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (e.getKey().trim().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void saveConn(AesDecryptor decryptor, Object encrypted, String fileName) throws IOException {
        if (encrypted == null) {
            System.out.println("[경고] 응답에 " + fileName + " 에 해당하는 접속 정보가 없습니다.");
            return;
        }
        Map<String, Object> result;
        if (encrypted instanceof Map) {
            // 객체 형태: 각 필드 값이 개별 암호화된 경우
            result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) encrypted).entrySet()) {
                result.put(e.getKey(), tryDecrypt(decryptor, e.getValue()));
            }
        } else {
            // 문자열 형태: 통째로 암호화된 경우
            String plain = decryptor.decrypt(String.valueOf(encrypted));
            result = toMap(plain);
        }
        Files.writeString(AppConfig.CONN_DIR.resolve(fileName), Json.pretty(result), StandardCharsets.UTF_8);
        System.out.println("접속 정보 복호화 → " + AppConfig.CONN_DIR.resolve(fileName) + " 키=" + result.keySet());
    }

    private static Object tryDecrypt(AesDecryptor decryptor, Object value) {
        if (!(value instanceof String)) {
            return value;
        }
        try {
            return decryptor.decrypt((String) value);
        } catch (RuntimeException e) {
            return value; // 암호화되지 않은 값
        }
    }

    /** 복호화 결과가 JSON 이면 그대로, key=value 나열이면 Map 으로, 그 외에는 raw 로 보관 */
    private static Map<String, Object> toMap(String plain) {
        String text = plain.trim();
        if (text.startsWith("{")) {
            return Json.parseObject(text);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        Matcher m = Pattern.compile("([A-Za-z_]+)\\s*[=:]\\s*([^;,\\n]+)").matcher(text);
        while (m.find()) {
            map.put(m.group(1), m.group(2).trim());
        }
        if (map.isEmpty() || text.startsWith("jdbc:")) {
            map.put("url", text);
        }
        map.put("_raw", text);
        return map;
    }

    private void writeApplicationProperties(String applicantKey, String name) throws IOException {
        Path example = AppConfig.CONFIG_DIR.resolve("application.example.properties");
        String template = Files.exists(AppConfig.PROPERTIES)
                ? Files.readString(AppConfig.PROPERTIES, StandardCharsets.UTF_8)
                : Files.readString(example, StandardCharsets.UTF_8);
        String text = template
                .replaceFirst("(?m)^applicant\\.key=.*$", "applicant.key=" + Matcher.quoteReplacement(applicantKey))
                .replaceFirst("(?m)^applicant\\.name=.*$", "applicant.name=" + Matcher.quoteReplacement(name));
        // Properties.load(Reader) 로 UTF-8 로 읽으므로 한글 그대로 저장 가능
        Files.writeString(AppConfig.PROPERTIES, text, StandardCharsets.UTF_8);
    }
}
