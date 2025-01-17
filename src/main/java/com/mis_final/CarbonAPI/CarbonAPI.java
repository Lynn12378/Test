package com.mis_final.CarbonAPI;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = "com.mis_final.CarbonAPI")
@EnableScheduling
public class CarbonAPI {
   public static void main(String[] args) {
       SpringApplication.run(CarbonAPI.class, args);
   }
}