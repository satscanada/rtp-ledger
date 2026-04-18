package com.rtpledger.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RtpSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RtpSimulatorApplication.class, args);
    }
}
