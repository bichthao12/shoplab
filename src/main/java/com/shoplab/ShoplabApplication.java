package com.shoplab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

import java.util.TimeZone;

/**
 * Modular monolith: mỗi package con trực tiếp (common, product, order, idempotency) là một module.
 * Cấu trúc module được Spring Modulith kiểm tra trong ModularityTests.
 * common là module dùng chung: luôn được dựng kèm khi test riêng từng module.
 */
@SpringBootApplication
@Modulithic(systemName = "ShopLab", sharedModules = "common")
public class ShoplabApplication {

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(ShoplabApplication.class, args);
	}

}
