package com.lewiswalker.savings;

import org.springframework.boot.SpringApplication;

public class TestSavingsAccountApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(SavingsAccountApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
