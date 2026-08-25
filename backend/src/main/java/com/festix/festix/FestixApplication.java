package com.festix.festix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FestixApplication {

	public static void main(String[] args) {
		SpringApplication.run(FestixApplication.class, args);
	}

}
