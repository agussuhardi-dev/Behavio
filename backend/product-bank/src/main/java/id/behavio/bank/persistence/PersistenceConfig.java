package id.behavio.bank.persistence;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/**
 * Mendaftarkan package entity JPA produk bank ke Hibernate. Diperlukan karena main class
 * app ada di package lain (id.behavio.app). Hanya :product-bank yang memakai JPA —
 * mesin generik & :product-qris murni JdbcClient.
 */
@Configuration
@EntityScan("id.behavio.bank.persistence")
public class PersistenceConfig {
}
