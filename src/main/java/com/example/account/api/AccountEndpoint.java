package com.example.account.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.example.account.application.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionStage;

@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/account")
public class AccountEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AccountEndpoint.class);

    private final ComponentClient client;

    public AccountEndpoint(ComponentClient client) {
        this.client = client;
    }

    @Get("/{id}")
    public CompletionStage<Integer> get(String id) {
        log.info("Get account with id [{}].", id);
        return client
            .forEventSourcedEntity(id)
            .method(Account::get)
            .invokeAsync()
            .thenApply(balance -> balance);
    }

    @Get("/{id}/verify/{amount}")
    public CompletionStage<Boolean> verify(String id, int amount) {
        log.info("Verify account with id [{}].", id);
        return client
            .forEventSourcedEntity(id)
            .method(Account::verifyFunds)
            .invokeAsync(amount)
            .thenApply(response -> response);
    }

    @Post("/{id}/create/{initBalance}")
    public CompletionStage<String> create(String id, int initBalance) {
        log.info("Create account with id [{}].", id);
        return client
            .forEventSourcedEntity(id)
            .method(Account::create)
            .invokeAsync(initBalance)
            .thenApply(response -> response);
    }

    @Post("/{id}/deposit/{amount}")
    public CompletionStage<Account.DepositResult> deposit(String id, int amount) {
        log.info("Deposit [{}] to account with id [{}].", amount, id);
        return client
            .forEventSourcedEntity(id)
            .method(Account::deposit)
            .invokeAsync(amount)
            .thenApply(response -> response);
    }

    @Post("/{id}/withdraw/{amount}")
    public CompletionStage<Account.WithdrawResult> withdraw(String id, int amount) {
        log.info("Withdraw [{}] from account with id [{}].", amount, id);
        return client
            .forEventSourcedEntity(id)
            .method(Account::withdraw)
            .invokeAsync(amount)
            .thenApply(response -> response);
    }

}
