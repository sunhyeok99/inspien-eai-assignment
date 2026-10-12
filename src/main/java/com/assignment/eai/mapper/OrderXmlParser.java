package com.assignment.eai.mapper;

import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.model.OrderHeader;
import com.assignment.eai.model.OrderItem;
import com.assignment.eai.model.OrderMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주문 요청 XML → OrderMessage (계층 구조 그대로).
 * <ul>
 *   <li>문자셋: Content-Type charset → XML 선언 encoding → UTF-8 순으로 결정 (EUC-KR 샘플 대응)</li>
 *   <li>예시처럼 루트 없이 HEADER/ITEM 이 나열된 경우도 처리하기 위해 가상 루트로 감싸서 파싱</li>
 *   <li>모든 값은 trim (예: &lt;STATUS&gt; 안의 줄바꿈/공백)</li>
 *   <li>XXE 방지를 위해 DTD/외부 엔티티 비활성화</li>
 * </ul>
 */
public final class OrderXmlParser {

    private static final Pattern XML_DECL = Pattern.compile("^\\s*<\\?xml[^>]*\\?>");
    private static final Pattern DECL_ENCODING = Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']");

    public OrderMessage parse(byte[] body, String contentTypeCharset) {
        if (body == null || body.length == 0) {
            throw new EaiException(ErrorCode.VALIDATION, "요청 본문이 비어 있습니다.");
        }
        String xml = decode(body, contentTypeCharset);
        xml = XML_DECL.matcher(xml).replaceFirst("");
        Document doc = parseDocument("<__ROOT__>" + xml + "</__ROOT__>");

        List<OrderHeader> headers = new ArrayList<>();
        NodeList hs = doc.getElementsByTagName("HEADER");
        for (int i = 0; i < hs.getLength(); i++) {
            Element h = (Element) hs.item(i);
            headers.add(new OrderHeader(text(h, "USER_ID"), text(h, "NAME"), text(h, "ADDRESS"), text(h, "STATUS")));
        }
        List<OrderItem> items = new ArrayList<>();
        NodeList is = doc.getElementsByTagName("ITEM");
        for (int i = 0; i < is.getLength(); i++) {
            Element it = (Element) is.item(i);
            items.add(new OrderItem(text(it, "USER_ID"), text(it, "ITEM_ID"), text(it, "ITEM_NAME"), text(it, "PRICE")));
        }
        return new OrderMessage(headers, items);
    }

    static String decode(byte[] body, String contentTypeCharset) {
        Charset cs = StandardCharsets.UTF_8;
        try {
            if (contentTypeCharset != null && !contentTypeCharset.isBlank()) {
                cs = Charset.forName(contentTypeCharset.trim());
            } else {
                // XML 선언은 ASCII 이므로 ISO-8859-1 로 앞부분만 읽어 encoding 확인
                String head = new String(body, 0, Math.min(body.length, 200), StandardCharsets.ISO_8859_1);
                Matcher decl = XML_DECL.matcher(head);
                if (decl.find()) {
                    Matcher enc = DECL_ENCODING.matcher(decl.group());
                    if (enc.find()) {
                        cs = Charset.forName(enc.group(1));
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw new EaiException(ErrorCode.VALIDATION, "지원하지 않는 문자셋: " + e.getMessage());
        }
        String s = new String(body, cs);
        return s.startsWith("\uFEFF") ? s.substring(1) : s; // BOM 제거
    }

    private static Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException e) {
            throw new EaiException(ErrorCode.VALIDATION, "XML 형식 오류: " + e.getMessage(), e);
        } catch (ParserConfigurationException | IOException e) {
            throw new EaiException(ErrorCode.MAPPING, "XML 파서 오류: " + e.getMessage(), e);
        }
    }

    /** 직계 자식 요소의 텍스트 (trim). 없으면 null */
    private static String text(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                return n.getTextContent() == null ? null : n.getTextContent().trim();
            }
        }
        return null;
    }
}
