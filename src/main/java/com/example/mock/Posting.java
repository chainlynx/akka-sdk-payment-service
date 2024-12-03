package com.example.mock;

import com.example.account.application.Account;
import akka.javasdk.client.ComponentClient;

import java.util.concurrent.CompletionStage;

import static com.example.account.application.Account.WithdrawResult.*;
import static com.example.account.application.Account.DepositResult.*;

public class Posting {

    /**
     * Posting is a component that adjusts the balances of the account(s) involved
     * in the transaction.
     *
     * For the purpose of this demo, posting will involve withdrawing funds from
     * the source account.
     *
     */

    public static CompletionStage<PostResult> post(Post.Funds request, ComponentClient client) {
        return client.forEventSourcedEntity(request.account)
            .method(Account::withdraw)
            .invokeAsync(request.amount)
            .thenApply(withdrawResult -> switch(withdrawResult) {
                case WithdrawSucceed __ -> new PostResult.Approved();
                case WithdrawFailed error -> new PostResult.Rejected(error.errorMsg());
            });
    }

    public static CompletionStage<PostResult> reversal(Post.Reversal request, ComponentClient client) {
        return client.forEventSourcedEntity(request.account)
            .method(Account::deposit)
            .invokeAsync(request.amount)
            .thenApply(depositResult -> switch(depositResult) {
                case DepositSucceed __ -> new PostResult.Approved();
                case DepositFailed error -> new PostResult.Rejected(error.errorMsg());
            });
    }

    public sealed interface Post {
        record Funds(String txId, String account, int amount) implements Post {}
        record Reversal(String txId, String account, int amount) implements Post {}
    }

    public sealed interface PostResult  {
        record Rejected(String reason) implements PostResult {}
        record Approved() implements PostResult {}
    }

}
