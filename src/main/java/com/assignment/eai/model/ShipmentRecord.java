package com.assignment.eai.model;

/** SHIPMENT_TB 한 행. PK = (SHIPMENT_ID, APPLICANT_KEY) */
public record ShipmentRecord(String shipmentId, String applicantKey, String orderId, String itemId, String address) {
}
