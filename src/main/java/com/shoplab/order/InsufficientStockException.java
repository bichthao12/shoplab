package com.shoplab.order;

public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, int requested, int available) {
        super("Sản phẩm " + sku + " không đủ hàng: cần " + requested + ", còn " + available);
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String getSku() { return sku; }
    public int getRequested() { return requested; }
    public int getAvailable() { return available; }
}
