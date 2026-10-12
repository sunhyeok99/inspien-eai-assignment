package com.assignment.eai.mapper;

import com.assignment.eai.model.PendingOrder;
import com.assignment.eai.model.ShipmentRecord;

/**
 * 쇼핑몰 주문(ORDER_TB) → 운송사 스키마(SHIPMENT_TB) 매핑.
 * ORDER_ID, ITEM_ID, ADDRESS 를 옮기고 SHIPMENT_ID 는 새로 채번, APPLICANT_KEY 는 고정.
 */
public final class ShipmentMapper {

    public ShipmentRecord toShipment(PendingOrder order, String shipmentId, String applicantKey) {
        return new ShipmentRecord(shipmentId, applicantKey, order.orderId(), order.itemId(), order.address());
    }
}
