package com.festix.festix;

import com.festix.festix.domain.reservation.ReservationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ReservationProperties.class)
public class FestixApplication {

	public static void main(String[] args) {
		SpringApplication.run(FestixApplication.class, args);
	}

}
