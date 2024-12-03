package com.example.mock;

import com.example.util.Validator;

import java.util.concurrent.CompletionStage;

public class Sanction {

    /**
     * Sanction is a component that checks if a transaction is sanctioned.
     *
     * Check if the transaction is sanctioned
     * - Check if the source or destination account is sanctioned
     *
     */

    public static CompletionStage<SanctionResult> check(Check.Accounts request) {
        return Validator
            .validate(
                Validator.isTrue(request.txId.isEmpty(), "Transaction ID is Required"),
                Validator.isTrue(request.source.isEmpty(), "Source Account is Required"),
                Validator.isTrue(request.destination.isEmpty(), "Destination Account is Required")
            )
            .handleAsync((result, err) -> switch(result){
                case SUCCESS -> new SanctionResult.Approved();
                case ERROR -> new SanctionResult.Rejected(err);
            });
    }

    public sealed interface Check {
        record Accounts(String txId, String source, String destination) implements Check {}
    }

    public sealed interface SanctionResult  {
        record Rejected(String reason) implements SanctionResult {}
        record Approved() implements SanctionResult {}
    }

}
