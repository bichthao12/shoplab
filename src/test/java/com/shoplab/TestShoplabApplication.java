package com.shoplab;

import org.springframework.boot.SpringApplication;

public class TestShoplabApplication {

	public static void main(String[] args) {
		SpringApplication.from(ShoplabApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
