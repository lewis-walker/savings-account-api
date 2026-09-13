package com.lewiswalker.savings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SavingsAccountApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SavingsAccountApiApplication.class, args);
	}

}
