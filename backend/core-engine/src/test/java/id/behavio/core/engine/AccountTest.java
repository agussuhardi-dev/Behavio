package id.behavio.core.engine;

import id.behavio.core.domain.Account;
import id.behavio.core.domain.InsufficientFundsException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private Account account(String balance) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "1234567890", "Joko", "IDR", new BigDecimal(balance));
    }

    @Test
    void debit_mengurangi_saldo() {
        Account a = account("100000");
        a.debit(new BigDecimal("30000"));
        assertEquals(new BigDecimal("70000"), a.balance());
    }

    @Test
    void debit_melebihi_saldo_dilempar() {
        Account a = account("10000");
        assertThrows(InsufficientFundsException.class,
                () -> a.debit(new BigDecimal("20000")));
    }
}
