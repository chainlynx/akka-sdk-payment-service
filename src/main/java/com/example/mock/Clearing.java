package com.example.mock;

import com.example.account.application.Account;
import akka.javasdk.client.ComponentClient;

import java.util.concurrent.CompletionStage;

import static com.example.account.application.Account.DepositResult.*;

public class Clearing {

    /**
     * Clearing is a component that clears a transaction by adjusting the balances of the
     * receiving account(s) involved in the transaction.
     *
     * For the purpose of this demo, clearing will involve depositing funds int the
     * destination account.
     *
     */

    public static CompletionStage<ClearingResult> clear(Clear.Funds request, ComponentClient client) {
        return client.forEventSourcedEntity(request.account)
            .method(Account::deposit)
            .invokeAsync(request.amount)
            .thenApply(depositResult -> switch(depositResult) {
                case DepositSucceed __ -> new ClearingResult.Accepted();
                case DepositFailed error -> new ClearingResult.Rejected(error.errorMsg());
            });
    }

    public sealed interface Clear {
        record Funds(String txId, String account, int amount) implements Clear {}
    }

    public sealed interface ClearingResult  {
        record Rejected(String reason) implements ClearingResult {}
        record Accepted() implements ClearingResult {}
    }


}
