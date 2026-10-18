-- ------------------------------------------------------------------
-- 시연용 조회 SQL (Oracle 19c)
-- SQL Developer / SQL*Plus 에서 아래 DEFINE 의 지원자 키만 바꾸고 실행 (F5: 스크립트 실행)
-- 다른 툴이면 &KEY 를 지원자 키로 바꿔서 실행
-- ------------------------------------------------------------------
DEFINE KEY = 'KSH*****'

-- 1) 주문 상태별 건수 : send 직후에는 N, batch 후에는 Y 로 바뀐다
SELECT TRIM(STATUS) AS STATUS, COUNT(*) AS CNT
  FROM ORDER_TB
 WHERE APPLICANT_KEY = '&KEY'
 GROUP BY TRIM(STATUS);

-- 2) 운송사 적재 건수 : batch 후 ORDER_TB 건수와 같아야 한다
SELECT COUNT(*) AS SHIPMENT_CNT
  FROM SHIPMENT_TB
 WHERE APPLICANT_KEY = '&KEY';

-- 3) 주문 상세 10건 (ROWNUM 은 ORDER BY 전에 잘리므로 FETCH FIRST 사용)
SELECT ORDER_ID, USER_ID, ITEM_ID, NAME, ADDRESS, ITEM_NAME, PRICE, STATUS, CREATE_TIME
  FROM ORDER_TB
 WHERE APPLICANT_KEY = '&KEY'
 ORDER BY ORDER_ID
 FETCH FIRST 10 ROWS ONLY;

-- 4) 주문자별 주문 건수·금액 (HEADER 1 : ITEM N 이 플랫하게 들어간 결과 확인)
SELECT USER_ID, NAME, COUNT(*) AS ITEM_CNT, SUM(TO_NUMBER(PRICE)) AS TOTAL_PRICE
  FROM ORDER_TB
 WHERE APPLICANT_KEY = '&KEY'
 GROUP BY USER_ID, NAME
 ORDER BY USER_ID;

-- ------------------------------------------------------------------
-- 정합성 점검 (배치 결과 대사)
-- 과제 환경은 주문 DB 와 운송 DB 가 같은 인스턴스라 JOIN 이 되지만,
-- 실제로 DB 가 분리되면 JOIN 이 불가능하다. 그래서 배치는 SHIPMENT_TB 에
-- 같은 ORDER_ID 가 이미 있는지 먼저 확인하고(멱등), 있으면 STATUS 만 보정한다.
-- ------------------------------------------------------------------

-- 5) 운송사에는 넘어갔는데 주문 상태가 아직 N 인 건 (STATUS UPDATE 실패 건, 다음 배치에서 보정 대상)
SELECT O.ORDER_ID, O.STATUS, S.SHIPMENT_ID
  FROM ORDER_TB O
  JOIN SHIPMENT_TB S ON S.APPLICANT_KEY = O.APPLICANT_KEY AND S.ORDER_ID = O.ORDER_ID
 WHERE O.APPLICANT_KEY = '&KEY'
   AND TRIM(O.STATUS) = 'N';

-- 6) 주문 상태는 Y 인데 운송사에 없는 건 (있으면 안 됨)
SELECT O.ORDER_ID, O.STATUS
  FROM ORDER_TB O
 WHERE O.APPLICANT_KEY = '&KEY'
   AND TRIM(O.STATUS) = 'Y'
   AND NOT EXISTS (SELECT 1 FROM SHIPMENT_TB S
                    WHERE S.APPLICANT_KEY = O.APPLICANT_KEY AND S.ORDER_ID = O.ORDER_ID);

-- 7) 같은 주문이 운송사에 중복 적재된 건 (있으면 안 됨)
SELECT ORDER_ID, COUNT(*) AS CNT
  FROM SHIPMENT_TB
 WHERE APPLICANT_KEY = '&KEY'
 GROUP BY ORDER_ID
HAVING COUNT(*) > 1;
