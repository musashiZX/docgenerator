package com.docgen;

import com.docgen.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(AppConfig.class)
public class Docx4jAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(Docx4jAgentApplication.class, args);
    }
}
