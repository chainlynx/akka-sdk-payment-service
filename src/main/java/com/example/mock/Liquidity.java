package com.example.mock;

import com.example.util.Validator;
import com.example.account.application.Account;
import akka.javasdk.client.ComponentClient;

import java.util.concurrent.CompletionStage;

public class Liquidity {

    /**
     * Liquidity is a component that checks if a transaction source account has liquidity.
     *
     * Check balance for the accounts involved in the transaction
     * - Ensure enough funds are available for the source of funds.
     *
     */

    public static CompletionStage<LiquidityResult> verify(Verify.Funds request, ComponentClient client) {
        return Validator
            .validate(
                Validator.isLtEqZero(request.amount, "Amount must be greater than 0")
            )
            .resolve(
                Validator.verify(
                    client
                        .forEventSourcedEntity(request.account)
                        .method(Account::verifyFunds),
                    request.amount,
                    "Source Account Funds Not Available"
                )
            )
            .handleAsync((result, err) -> switch(result){
                case SUCCESS -> new LiquidityResult.Approved();
                case ERROR -> new LiquidityResult.Rejected(err);
            });
    }

    public sealed interface Verify {
        record Funds(String txId, String account, int amount) implements Verify {}
    }

    public sealed interface LiquidityResult  {
        record Rejected(String reason) implements LiquidityResult {}
        record Approved() implements LiquidityResult {}
    }

}
