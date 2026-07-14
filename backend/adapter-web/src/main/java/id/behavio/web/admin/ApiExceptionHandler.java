package id.behavio.web.admin;

import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Penanganan global untuk validasi bisnis Admin API.
 *
 * CATATAN PENTING (gotcha Spring): bean {@code @Repository} secara OTOMATIS
 * diproksi dengan exception-translation JPA/Hibernate — {@link IllegalArgumentException}
 * yang dilempar dari bean semacam itu (mis. {@code EndpointRegistryJdbc},
 * {@code AccountAdminJdbc}) diterjemahkan Spring menjadi
 * {@link InvalidDataAccessApiUsageException} SEBELUM sampai ke controller mana pun.
 * Akibatnya {@code catch (IllegalArgumentException e)} lokal di controller TIDAK
 * PERNAH match, dan request jatuh jadi 500 mentah, bukan 400 yang rapi. Handler
 * global ini menangkap kedua tipe sekaligus agar konsisten di semua Admin API.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({ IllegalArgumentException.class, InvalidDataAccessApiUsageException.class })
    public ResponseEntity<?> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
