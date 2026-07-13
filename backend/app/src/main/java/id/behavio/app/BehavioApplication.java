package id.behavio.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Titik masuk aplikasi. Merakit core-engine + semua adapter (Hexagonal).
 * Scan seluruh id.behavio agar adapter (web/webhook/persistence) terdeteksi.
 */
@SpringBootApplication(scanBasePackages = "id.behavio")
public class BehavioApplication {

    public static void main(String[] args) {
        SpringApplication.run(BehavioApplication.class, args);
    }
}
