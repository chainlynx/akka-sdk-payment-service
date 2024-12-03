package com.example.transaction;

import akka.javasdk.testkit.TestKitSupport;
import com.example.transaction.application.TransactionWorkflow;
import com.example.transaction.application.TransactionWorkflow.State;
import com.example.account.application.Account;
import com.example.transaction.domain.Transaction;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static com.example.transaction.application.TransactionWorkflow.State.Status.*;

public class TransactionWorkflowIntegrationTest extends TestKitSupport {

    private static final Logger log = LoggerFactory.getLogger(TransactionWorkflowIntegrationTest.class);

    @Test
    public void shouldTransferMoney() {
        var accountId1 = randomId();
        var accountId2 = randomId();

        createAccount(accountId1, 100);
        createAccount(accountId2, 100);

        var txId = randomId();
        var payment = new Transaction.Request(
            accountId1,
            accountId2,
            "1",
            10
        );

        Transaction.Response response = await(
            componentClient
                .forWorkflow(txId)
                .method(TransactionWorkflow::process)
                .invokeAsync(payment)
        );

        assertThat(response).asInstanceOf(InstanceOfAssertFactories.type(Transaction.Response.Received.class))
            .extracting(Transaction.Response.Received::status)
            .isEqualTo("VALIDATING_REQUEST");

        Awaitility.await()
            .atMost(2, TimeUnit.of(SECONDS))
            .untilAsserted(() -> {
                var balance1 = getAccountBalance(accountId1);
                var balance2 = getAccountBalance(accountId2);
                log.info("Awaiting assertions: balance1={}, balance2={}", balance1, balance2);
                assertThat(balance1).isEqualTo(90);
                assertThat(balance2).isEqualTo(110);
            });
    }

    @Test
    public void shouldFailValidationCheck_MissingAccounts() {
        var accountId1 = randomId();
        var accountId2 = randomId();

        //Note: no accounts created here, on purpose...

        var txId = randomId();
        var payment = new Transaction.Request(
            accountId1,
            accountId2,
            "1",
            10
        );//both not exists

        Transaction.Response response = await(
            componentClient
                .forWorkflow(txId)
                .method(TransactionWorkflow::process)
                .invokeAsync(payment)
        );

        assertThat(response).asInstanceOf(InstanceOfAssertFactories.type(Transaction.Response.Received.class))
            .extracting(Transaction.Response.Received::status)
            .isEqualTo("VALIDATING_REQUEST");

        Awaitility.await()
            .atMost(10, TimeUnit.of(SECONDS))
            .ignoreExceptions()
            .untilAsserted(() -> {
                State state = getTransaction(txId);
                log.info("Awaiting assertions: state={}", state);
                assertThat(state.status()).isEqualTo(VALIDATION_FAILED);
            });
    }


    private String randomId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void createAccount(String accountId, int amount) {
        String response = await(
            componentClient
                .forEventSourcedEntity(accountId)
                .method(Account::create)
                .invokeAsync(amount)
        );
        assertThat(response).contains("ok");
    }

    private int getAccountBalance(String accountId) {
        return await(
            componentClient
                .forEventSourcedEntity(accountId)
                .method(Account::get)
                .invokeAsync()
        );
    }

    private State getTransaction(String txId) {
        return await(
            componentClient
                .forWorkflow(txId)
                .method(TransactionWorkflow::get)
                .invokeAsync()
        );
    }

}