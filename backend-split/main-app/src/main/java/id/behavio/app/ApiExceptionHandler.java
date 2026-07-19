package id.behavio.app;

import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Penanganan global validasi bisnis Admin API — satu untuk seluruh aplikasi (lintas
 * bank & qris), di main-app agar tak ada dua @RestControllerAdvice yang berebut.
 *
 * <p>Gotcha Spring: bean {@code @Repository} otomatis diproksi exception-translation
 * JPA/Hibernate — {@link IllegalArgumentException} dari bean semacam itu diterjemahkan
 * jadi {@link InvalidDataAccessApiUsageException} sebelum sampai ke controller, sehingga
 * {@code catch} lokal tak pernah match dan request jatuh 500. Handler ini menangkap
 * kedua tipe agar konsisten jadi 400.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({ IllegalArgumentException.class, InvalidDataAccessApiUsageException.class })
    public ResponseEntity<?> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
