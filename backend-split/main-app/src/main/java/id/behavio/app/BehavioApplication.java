package id.behavio.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * Titik masuk backend-split — launcher tipis (MIGRATION-PLAN.md §4).
 *
 * <p>Merakit dua module produk yang berdiri sendiri: BANK ({@code id.behavio.bank.*},
 * module {@code :simulator}) dan QRIS ({@code id.behavio.qris.*}, module {@code :qris}).
 * Masing-masing membawa SALINAN platform-nya sendiri.
 *
 * <p><b>Kenapa {@link FullyQualifiedAnnotationBeanNameGenerator}.</b> Karena tiap produk
 * menyalin lapisan generik yang sama, ada dua kelas @Component bernama sederhana identik
 * (mis. {@code ProductRegistry}) di package berbeda. Generator nama default memakai nama
 * sederhana → dua bean "productRegistry" → bentrok. Nama ber-FQN membuat keduanya unik.
 * (Injeksi tetap by-type; tipe-tipe itu sudah berbeda karena di-repackage.)
 */
@SpringBootApplication(
        scanBasePackages = "id.behavio",
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class BehavioApplication {

    public static void main(String[] args) {
        SpringApplication.run(BehavioApplication.class, args);
    }
}
