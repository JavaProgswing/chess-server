package org.yashasvi.chessserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChessServerApplication {

    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--selenium=")) {
                String[] parts = arg.split("=", 2);
                if (parts.length == 2) {
                    System.setProperty("selenium.mode", parts[1]);
                }
            } else if (arg.startsWith("--remote-url=")) {
                String[] parts = arg.split("=", 2);
                if (parts.length == 2) {
                    System.setProperty("selenium.remote.url", parts[1]);
                }
            }
        }
        SpringApplication.run(ChessServerApplication.class, args);
    }

}
