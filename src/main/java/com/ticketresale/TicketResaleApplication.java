package com.ticketresale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TicketResaleApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketResaleApplication.class, args);
    }
}
