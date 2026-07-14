package id.behavio.persistence;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/**
 * Mendaftarkan package entity JPA (id.behavio.persistence) ke Hibernate. Diperlukan
 * karena main class app ada di package lain (id.behavio.app), sedangkan entity di sini.
 */
@Configuration
@EntityScan("id.behavio.persistence")
public class PersistenceConfig {
}
