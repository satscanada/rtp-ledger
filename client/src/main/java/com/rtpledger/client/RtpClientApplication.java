package com.rtpledger.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RtpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(RtpClientApplication.class, args);
    }
}
